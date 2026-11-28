package ph.com.ridgewell.county.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Real PostgreSQL fixtures let each test use distinct owners without relying on test order. */
@QuarkusTest
class IsolationApiTest {
  @Inject AgroalDataSource dataSource;

  record Fixture(long tenant, long product, long reseller, String token) {}

  private Fixture fixture(String subject) throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      long tenant = TestOwners.createBusiness(c, subject);
      long product =
          insert(
              c,
              "INSERT INTO product(tenant_id,name,unit,price) VALUES (?, 'Hamonado','pack',120)"
                  + " RETURNING id",
              tenant);
      String token = UUID.randomUUID().toString().replace("-", "").substring(0, 22);
      long reseller =
          insert(
              c,
              "INSERT INTO reseller(tenant_id,name,mobile,token) VALUES (?, 'Rosa',"
                  + " '+639171234567',?) RETURNING id",
              tenant,
              token);
      return new Fixture(tenant, product, reseller, token);
    }
  }

  private long insert(Connection c, String sql, Object... values) throws SQLException {
    try (var statement = c.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
      try (var rs = statement.executeQuery()) {
        assertTrue(rs.next());
        return rs.getLong(1);
      }
    }
  }

  private String order(long product, int quantity, String key) {
    return "{\"lines\":[{\"productId\":"
        + product
        + ",\"quantity\":"
        + quantity
        + "}],\"note\":\"\",\"idempotencyKey\":\""
        + key
        + "\"}";
  }

  private long place(Fixture f, String key) {
    return given()
        .basePath("")
        .contentType("application/json")
        .body(order(f.product, 1, key))
        .post("/api/order/" + f.token + "/orders")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getLong("id");
  }

  @Test
  @TestSecurity(
      user = "isolation-foreign",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "isolation-foreign")})
  void foreignTenantReadsAndWritesAreNotFound() throws SQLException {
    fixture("isolation-foreign");
    Fixture other = fixture("foreign-" + UUID.randomUUID());
    long id = place(other, UUID.randomUUID().toString());
    given().basePath("").get("/api/orders/" + id).then().statusCode(404);
    for (String action : new String[] {"confirm", "cancel", "mark-paid"})
      given().basePath("").post("/api/orders/" + id + "/" + action).then().statusCode(404);
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"paid\":true}")
        .post("/api/orders/" + id + "/deliver")
        .then()
        .statusCode(404);
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"Changed\",\"unit\":\"pack\",\"price\":1,\"active\":false}")
        .put("/api/products/" + other.product)
        .then()
        .statusCode(404);
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"Changed\",\"mobile\":\"09171234568\"}")
        .put("/api/resellers/" + other.reseller)
        .then()
        .statusCode(404);
    for (String action : new String[] {"block", "unblock", "reissue-link"})
      given()
          .basePath("")
          .post("/api/resellers/" + other.reseller + "/" + action)
          .then()
          .statusCode(404);
    given()
        .basePath("")
        .get("/api/order/" + other.token + "/orders/" + id)
        .then()
        .statusCode(200)
        .body("status", is("placed"));
  }

  @Test
  void publicOrderingDoesNotRequireOwnerAuthenticationAndResellersAreIsolated()
      throws SQLException {
    Fixture f = fixture("public-" + UUID.randomUUID());
    String secondToken = UUID.randomUUID().toString().replace("-", "").substring(0, 22);
    try (var c = dataSource.getConnection()) {
      insert(
          c,
          "INSERT INTO reseller(tenant_id,name,mobile,token) VALUES (?, 'Ben', '+639171234568',?)"
              + " RETURNING id",
          f.tenant,
          secondToken);
    }
    given().basePath("").get("/api/order/" + f.token).then().statusCode(200);
    String key = UUID.randomUUID().toString();
    long id = place(f, key);
    given().basePath("").get("/api/order/" + f.token + "/orders/" + id).then().statusCode(200);
    given().basePath("").get("/api/order/" + secondToken + "/orders/" + id).then().statusCode(404);
    given()
        .basePath("")
        .post("/api/order/" + secondToken + "/orders/" + id + "/cancel")
        .then()
        .statusCode(404);
    // An idempotency key is not a credential for the original reseller's order.
    given()
        .basePath("")
        .contentType("application/json")
        .body(order(f.product, 1, key))
        .post("/api/order/" + secondToken + "/orders")
        .then()
        .statusCode(409);
    given()
        .basePath("")
        .get("/api/order/" + f.token + "/orders/" + id)
        .then()
        .statusCode(200)
        .body("status", is("placed"));
    given().basePath("").get("/api/products").then().statusCode(401);
  }

  @Test
  @TestSecurity(
      user = "isolation-revoke",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "isolation-revoke")})
  void blockedAndReissuedTokensCannotReadWriteOrReplay() throws SQLException {
    Fixture f = fixture("isolation-revoke");
    String key = UUID.randomUUID().toString();
    long id = place(f, key);
    given().basePath("").post("/api/resellers/" + f.reseller + "/block").then().statusCode(200);
    assertTokenDead(f.token, f.product, key, id);
    given().basePath("").post("/api/resellers/" + f.reseller + "/unblock").then().statusCode(200);
    given().basePath("").get("/api/order/" + f.token).then().statusCode(200);
    String replacement =
        given()
            .basePath("")
            .post("/api/resellers/" + f.reseller + "/reissue-link")
            .then()
            .statusCode(200)
            .extract()
            .path("token");
    assertNotEquals(f.token, replacement);
    assertTokenDead(f.token, f.product, key, id);
    given().basePath("").get("/api/order/" + replacement).then().statusCode(200);
  }

  private int cancelByReseller(Fixture f, long order) {
    return given()
        .basePath("")
        .post("/api/order/" + f.token + "/orders/" + order + "/cancel")
        .then()
        .extract()
        .statusCode();
  }

  @Test
  @TestSecurity(
      user = "isolation-history-b",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "isolation-history-b")})
  void anotherTenantsOwnerCannotReadOrderHistory() throws SQLException {
    Fixture b = fixture("isolation-history-b");
    Fixture a = fixture("history-a-" + UUID.randomUUID());
    long orderA = place(a, UUID.randomUUID().toString());
    assertEquals(200, cancelByReseller(a, orderA));
    // A's order has its event, read through A's link.
    given()
        .basePath("")
        .get("/api/order/" + a.token + "/orders/" + orderA)
        .then()
        .statusCode(200)
        .body("events", hasSize(1));

    given().basePath("").get("/api/orders/" + orderA).then().statusCode(404);

    // B's own order carries only B's event, none of A's.
    long orderB = place(b, UUID.randomUUID().toString());
    assertEquals(200, cancelByReseller(b, orderB));
    given()
        .basePath("")
        .get("/api/orders/" + orderB)
        .then()
        .statusCode(200)
        .body("events", hasSize(1));
    try (var c = dataSource.getConnection()) {
      assertEquals(
          1L,
          TestSql.one(c, "SELECT count(*) AS n FROM order_event WHERE tenant_id=?", b.tenant)
              .get("n"));
    }
  }

  @Test
  void anotherResellersLinkCannotReadOrderHistory() throws SQLException {
    Fixture f = fixture("history-reseller-" + UUID.randomUUID());
    String otherToken = UUID.randomUUID().toString().replace("-", "").substring(0, 22);
    try (var c = dataSource.getConnection()) {
      insert(
          c,
          "INSERT INTO reseller(tenant_id,name,mobile,token) VALUES (?, 'Ben', '+639171234568',?)"
              + " RETURNING id",
          f.tenant,
          otherToken);
    }
    long order = place(f, UUID.randomUUID().toString());
    assertEquals(200, cancelByReseller(f, order));
    given()
        .basePath("")
        .get("/api/order/" + f.token + "/orders/" + order)
        .then()
        .statusCode(200)
        .body("events", hasSize(1));

    given().basePath("").get("/api/order/" + otherToken + "/orders/" + order).then().statusCode(404);
    given()
        .basePath("")
        .post("/api/order/" + otherToken + "/orders/" + order + "/cancel")
        .then()
        .statusCode(404);
    given()
        .basePath("")
        .get("/api/order/" + f.token + "/orders/" + order)
        .then()
        .body("events", hasSize(1));
  }

  private void assertTokenDead(String token, long product, String key, long order) {
    given().basePath("").get("/api/order/" + token).then().statusCode(404);
    given().basePath("").get("/api/order/" + token + "/orders/" + order).then().statusCode(404);
    given()
        .basePath("")
        .post("/api/order/" + token + "/orders/" + order + "/cancel")
        .then()
        .statusCode(404);
    given()
        .basePath("")
        .contentType("application/json")
        .body(order(product, 1, key))
        .post("/api/order/" + token + "/orders")
        .then()
        .statusCode(404);
  }

  @Test
  @TestSecurity(
      user = "isolation-validation",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "isolation-validation")})
  void validatesPricesMobilesQuantitiesAndInactiveProducts() throws SQLException {
    Fixture f = fixture("isolation-validation");
    for (String price : new String[] {"0", "-1", "0.001", "10000000000"}) {
      given()
          .basePath("")
          .contentType("application/json")
          .body("{\"name\":\"Bad price\",\"unit\":\"pack\",\"price\":" + price + "}")
          .post("/api/products")
          .then()
          .statusCode(422);
    }
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"Bad mobile\",\"mobile\":\"not a number\"}")
        .post("/api/resellers")
        .then()
        .statusCode(422);
    for (int qty : new int[] {-1, 0, 10000})
      given()
          .basePath("")
          .contentType("application/json")
          .body(order(f.product, qty, UUID.randomUUID().toString()))
          .post("/api/order/" + f.token + "/orders")
          .then()
          .statusCode(422);
    String duplicate =
        "{\"lines\":[{\"productId\":"
            + f.product
            + ",\"quantity\":1},{\"productId\":"
            + f.product
            + ",\"quantity\":2}],\"idempotencyKey\":\""
            + UUID.randomUUID()
            + "\"}";
    given()
        .basePath("")
        .contentType("application/json")
        .body(duplicate)
        .post("/api/order/" + f.token + "/orders")
        .then()
        .statusCode(422);
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"Hamonado\",\"unit\":\"pack\",\"price\":120,\"active\":false}")
        .put("/api/products/" + f.product)
        .then()
        .statusCode(200);
    given()
        .basePath("")
        .contentType("application/json")
        .body(order(f.product, 1, UUID.randomUUID().toString()))
        .post("/api/order/" + f.token + "/orders")
        .then()
        .statusCode(422);
  }

  @Test
  @TestSecurity(
      user = "isolation-fractional",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "isolation-fractional")})
  void anotherTenantsFractionalLinesAreNotInTheProductionRun() throws SQLException {
    Fixture own = fixture("isolation-fractional");
    Fixture other = fixture("fractional-foreign-" + UUID.randomUUID());
    String date;
    try (Connection c = dataSource.getConnection()) {
      for (Fixture f : new Fixture[] {own, other})
        TestSql.execute(c, "UPDATE product SET unit='kg' WHERE id=?", f.product);
      long mine = placeFraction(own, "0.5");
      long theirs = placeFraction(other, "1.5");
      date = TestSql.one(c, "SELECT delivery_date::text AS d FROM sales_order WHERE id=?", mine)
          .get("d").toString();
      TestSql.execute(
          c,
          "UPDATE sales_order SET status='confirmed', confirmed_at=now() WHERE id IN (?,?)",
          mine,
          theirs);
    }
    given()
        .basePath("")
        .get("/api/production-run?date=" + date)
        .then()
        .statusCode(200)
        .body("lines", hasSize(1))
        .body("lines[0].productId", is((int) own.product))
        .body("lines[0].quantity", is(0.5f))
        .body("lines[0].orderCount", is(1));
  }

  private long placeFraction(Fixture f, String quantity) {
    return given()
        .basePath("")
        .contentType("application/json")
        .body(
            "{\"lines\":[{\"productId\":"
                + f.product
                + ",\"quantity\":"
                + quantity
                + "}],\"idempotencyKey\":\""
                + UUID.randomUUID()
                + "\"}")
        .post("/api/order/" + f.token + "/orders")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getLong("id");
  }

  @Test
  @TestSecurity(
      user = "isolation-request-scope",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "isolation-request-scope")})
  void callerSuppliedTenantCannotInfluenceOwnerOrTokenScope() throws SQLException {
    Fixture own = fixture("isolation-request-scope");
    Fixture other = fixture("scope-foreign-" + UUID.randomUUID());
    // Query/header tenant hints must never change the identity-derived scope.
    given()
        .basePath("")
        .queryParam("tenantId", other.tenant)
        .header("X-Tenant-Id", other.tenant)
        .get("/api/products")
        .then()
        .statusCode(200)
        .body("id", hasItem((int) own.product))
        .body("id", not(hasItem((int) other.product)));
    // Neither an owner credential nor a foreign product ID can cross the token's tenant.
    given()
        .basePath("")
        .contentType("application/json")
        .body(order(other.product, 1, UUID.randomUUID().toString()))
        .post("/api/order/" + own.token + "/orders")
        .then()
        .statusCode(404);
    String key = UUID.randomUUID().toString();
    String body =
        order(own.product, 1, key)
            .replace("{\"lines\"", "{\"tenantId\":" + other.tenant + ",\"lines\"");
    var response =
        given()
            .basePath("")
            .contentType("application/json")
            .body(body)
            .post("/api/order/" + own.token + "/orders");
    // Rejecting unknown fields or ignoring the hint are both safe; accepting it as authority is
    // not.
    assertTrue(
        response.statusCode() == 201
            || response.statusCode() == 400
            || response.statusCode() == 422);
    if (response.statusCode() == 201) {
      long id = response.jsonPath().getLong("id");
      try (var c = dataSource.getConnection();
          var s = c.prepareStatement("SELECT tenant_id FROM sales_order WHERE id=?")) {
        s.setLong(1, id);
        try (var rs = s.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(own.tenant, rs.getLong(1));
        }
      }
    }
  }
}
