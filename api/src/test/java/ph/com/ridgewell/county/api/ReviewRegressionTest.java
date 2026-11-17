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

/** Regression coverage for concrete findings from the independent backend review. */
@QuarkusTest
class ReviewRegressionTest {
  @Inject AgroalDataSource source;

  record Fixture(long tenant, long product, long reseller, String token) {}

  private Fixture fixture(String subject) throws SQLException {
    try (Connection c = source.getConnection()) {
      long tenant = TestOwners.createBusiness(c, subject);
      long product =
          insert(
              c,
              "INSERT INTO product(tenant_id,name,unit,price) VALUES (?, 'Original"
                  + " name','pack',120) RETURNING id",
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

  private long insert(Connection c, String sql, Object... args) throws SQLException {
    try (var s = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
      try (var r = s.executeQuery()) {
        assertTrue(r.next());
        return r.getLong(1);
      }
    }
  }

  private io.restassured.path.json.JsonPath place(Fixture f, int quantity) {
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
        .jsonPath();
  }

  private void invalid(String method, String path, String body) {
    given()
        .basePath("")
        .contentType("application/json")
        .body(body)
        .request(method, path)
        .then()
        .statusCode(422)
        .contentType("application/problem+json")
        .body("status", is(422));
  }

  @Test
  @TestSecurity(
      user = "review-invalid",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "review-invalid")})
  void malformedDatesAndCutoffReturnValidationProblems() throws SQLException {
    fixture("review-invalid");
    for (String cutoff : new String[] {"nonsense", "25:00", ""}) {
      String body = "{\"name\":\"Business\",\"cutoff\":\"" + cutoff + "\",\"deliveryDays\":[1]}";
      invalid("POST", "/api/tenants", body);
      invalid("PUT", "/api/settings", body);
    }
    for (String date : new String[] {"yesterday", "2026-02-30", ""}) {
      given()
          .basePath("")
          .queryParam("date", date)
          .get("/api/orders")
          .then()
          .statusCode(422)
          .body("status", is(422));
      given()
          .basePath("")
          .queryParam("date", date)
          .get("/api/production-run")
          .then()
          .statusCode(422)
          .body("status", is(422));
    }
  }

  @Test
  @TestSecurity(
      user = "review-null",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "review-null")})
  void nullBodiesAndContainerElementsAreValidationProblems() throws SQLException {
    Fixture f = fixture("review-null");
    for (String path :
        new String[] {
          "/api/tenants", "/api/products", "/api/resellers", "/api/order/" + f.token + "/orders"
        }) invalid("POST", path, "null");
    invalid("PUT", "/api/settings", "null");
    invalid("PUT", "/api/products/" + f.product, "null");
    invalid("PUT", "/api/resellers/" + f.reseller, "null");
    invalid("POST", "/api/orders/1/deliver", "null");
    for (String body :
        new String[] {
          "{\"name\":\"Business\",\"cutoff\":null,\"deliveryDays\":[1]}",
          "{\"name\":\"Business\",\"cutoff\":\"16:00\",\"deliveryDays\":null}",
          "{\"name\":\"Business\",\"cutoff\":\"16:00\",\"deliveryDays\":[null]}"
        }) {
      invalid("POST", "/api/tenants", body);
      invalid("PUT", "/api/settings", body);
    }
    for (String lines : new String[] {"null", "[null]"})
      invalid(
          "POST",
          "/api/order/" + f.token + "/orders",
          "{\"lines\":" + lines + ",\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}");
  }

  @Test
  @TestSecurity(
      user = "review-production",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "review-production")})
  void productionAggregatesOneProductAcrossHistoricalNames() throws SQLException {
    Fixture f = fixture("review-production");
    var first = place(f, 2);
    long firstId = first.getLong("id");
    String date = first.getString("deliveryDate");
    given().basePath("").post("/api/orders/" + firstId + "/confirm").then().statusCode(200);
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"New product name\",\"unit\":\"pack\",\"price\":125}")
        .put("/api/products/" + f.product)
        .then()
        .statusCode(200);
    var second = place(f, 3);
    long secondId = second.getLong("id");
    assertEquals(
        date, second.getString("deliveryDate"), "Fixture orders must share a delivery date");
    given().basePath("").post("/api/orders/" + secondId + "/confirm").then().statusCode(200);
    given()
        .basePath("")
        .queryParam("date", date)
        .get("/api/production-run")
        .then()
        .statusCode(200)
        .body("lines.size()", is(1))
        .body("lines[0].productId", is((int) f.product))
        .body("lines[0].productName", is("New product name"))
        .body("lines[0].quantity", is(5))
        .body("lines[0].orderCount", is(2));
    // Fixing aggregation must not rewrite immutable order-line snapshots.
    given()
        .basePath("")
        .get("/api/orders/" + firstId)
        .then()
        .statusCode(200)
        .body("lines[0].productName", is("Original name"))
        .body("total", is(240.0f));
  }

  @Test
  @TestSecurity(
      user = "review-links",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "review-links")})
  void resellerListCanCopyExistingLinkWithoutRotatingIt() throws SQLException {
    Fixture f = fixture("review-links");
    for (int read = 0; read < 2; read++)
      given()
          .basePath("")
          .get("/api/resellers")
          .then()
          .statusCode(200)
          .body("size()", is(1))
          .body("[0].id", is((int) f.reseller))
          .body("[0].orderLink", endsWith("/order/" + f.token));
    given()
        .basePath("")
        .get("/api/order/" + f.token)
        .then()
        .statusCode(200)
        .body("resellerName", is("Rosa"));
    try (var c = source.getConnection();
        var s = c.prepareStatement("SELECT token FROM reseller WHERE id=?")) {
      s.setLong(1, f.reseller);
      try (var r = s.executeQuery()) {
        assertTrue(r.next());
        assertEquals(f.token, r.getString(1));
      }
    }
  }

  @Test
  @TestSecurity(
      user = "review-status",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "review-status")})
  void acceptedUppercaseStatusFiltersTheSameAsLowercase() throws SQLException {
    Fixture f = fixture("review-status");
    long id = place(f, 1).getLong("id");
    for (String status : new String[] {"placed", "PLACED"})
      given()
          .basePath("")
          .queryParam("status", status)
          .get("/api/orders")
          .then()
          .statusCode(200)
          .body("size()", is(1))
          .body("[0].id", is((int) id))
          .body("[0].status", is("placed"));
    given().basePath("").queryParam("status", "unknown").get("/api/orders").then().statusCode(422);
  }
}
