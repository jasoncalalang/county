package ph.com.ridgewell.county.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR 0018: a reseller link works for 90 days, and an expired link is a blocked one. */
@QuarkusTest
class ResellerLinkApiTest {
  @Inject AgroalDataSource source;

  private static final String JSON = "application/json";

  private static void assertAboutNinetyDaysAhead(Instant expiry) {
    Duration ahead = Duration.between(Instant.now(), expiry);
    assertTrue(
        ahead.minus(Duration.ofDays(90)).abs().compareTo(Duration.ofMinutes(1)) < 0,
        "expected about 90 days, got " + ahead);
  }

  private void expire(String token) throws SQLException {
    try (Connection c = source.getConnection()) {
      assertEquals(
          1,
          TestSql.execute(
              c,
              "UPDATE reseller SET token_expires_at = now() - interval '1 minute' WHERE token=?",
              token));
    }
  }

  private Instant expiryOf(String token) throws SQLException {
    try (Connection c = source.getConnection()) {
      return ((Timestamp)
              TestSql.one(c, "SELECT token_expires_at AS at FROM reseller WHERE token=?", token)
                  .get("at"))
          .toInstant();
    }
  }

  private static String place(long product, UUID key) {
    return "{\"lines\":[{\"productId\":"
        + product
        + ",\"quantity\":1}],\"idempotencyKey\":\""
        + key
        + "\"}";
  }

  /** The owner's business with one reseller made through the API. */
  private static io.restassured.path.json.JsonPath newReseller() {
    given()
        .basePath("")
        .contentType(JSON)
        .body("{\"name\":\"Nena\",\"cutoff\":\"16:00\",\"deliveryDays\":[1,2,3,4,5,6,7]}")
        .post("/api/tenants")
        .then()
        .statusCode(201);
    return given()
        .basePath("")
        .contentType(JSON)
        .body("{\"name\":\"Rosa\",\"mobile\":\"09171234567\"}")
        .post("/api/resellers")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath();
  }

  @Test
  @TestSecurity(
      user = "link-new",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "link-new")})
  void aNewLinkExpiresInNinetyDaysAndStopsWorkingThen() throws SQLException {
    var reseller = newReseller();
    String token = reseller.getString("token");

    String expiresAt = reseller.getString("linkExpiresAt");
    assertNotNull(expiresAt, "linkExpiresAt is missing from the new reseller");
    assertAboutNinetyDaysAhead(Instant.parse(expiresAt));
    given()
        .basePath("")
        .get("/api/resellers")
        .then()
        .statusCode(200)
        .body("find { it.id == " + reseller.getLong("id") + " }.linkExpiresAt", is(expiresAt));
    given().basePath("").get("/api/order/" + token).then().statusCode(200);

    expire(token);

    given().basePath("").get("/api/order/" + token).then().statusCode(404);
  }

  /** Real rows behind every id, so that a 404 can only come from the link. */
  record Rows(
      DiscrepancyFixtures.Shop shop, long order, long quote, String token, UUID usedKey) {}

  private Rows rows(String subject) throws SQLException {
    try (Connection c = source.getConnection()) {
      var shop = DiscrepancyFixtures.shop(c, subject);
      var order =
          DiscrepancyFixtures.delivered(
              c, shop, shop.rosa(), DiscrepancyFixtures.ham(shop, "10"));
      DiscrepancyFixtures.report(c, shop, order, "owner");
      // The fixture order took number 1 behind the business's counter.
      TestSql.execute(c, "UPDATE business SET next_order_number = 2 WHERE id=?", shop.tenant());
      long quote =
          TestSql.id(
              TestSql.one(
                  c,
                  "INSERT INTO quote(tenant_id,reseller_id,number,created_at,expires_on,"
                      + "delivery_date,status,total,sent_at) VALUES (?,?,1,now(),current_date + 7,"
                      + "current_date + 1,'sent',120,now()) RETURNING id",
                  shop.tenant(),
                  shop.rosa()));
      TestSql.execute(
          c,
          "INSERT INTO quote_line(tenant_id,quote_id,product_id,product_name,unit,quantity,"
              + "price,total) VALUES (?,?,?,'Hamonado','pack',1,120,120)",
          shop.tenant(),
          quote,
          shop.ham());
      UUID usedKey = UUID.randomUUID();
      given()
          .basePath("")
          .contentType(JSON)
          .body(place(shop.ham(), usedKey))
          .post("/api/order/" + shop.rosaToken() + "/orders")
          .then()
          .statusCode(201);
      return new Rows(shop, order.id(), quote, shop.rosaToken(), usedKey);
    }
  }

  /** A valid body for every reseller operation that takes one. */
  private static String body(String operation, Rows r) {
    return switch (operation) {
      case "post /api/order/{token}/orders" -> place(r.shop.ham(), r.usedKey);
      case "post /api/order/{token}/orders/{id}/discrepancy" ->
          DiscrepancyFixtures.body(DiscrepancyFixtures.line(r.shop.ham(), "5"));
      case "post /api/order/{token}/orders/{id}/discrepancy/approve",
          "post /api/order/{token}/orders/{id}/discrepancy/decline" -> "{}";
      case "post /api/order/{token}/quotes/{id}/accept" ->
          "{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}";
      default ->
          throw new AssertionError("Add a valid body for " + operation + " to this test");
    };
  }

  private static Response call(String method, String path, Rows r, String operation, boolean hasBody) {
    String url = path.replace("{token}", r.token);
    if (url.contains("{id}")) {
      long id = path.contains("/quotes/") ? r.quote : r.order;
      url = url.replace("{id}", String.valueOf(id));
    }
    assertFalse(url.contains("{"), "Teach this test the parameters of " + path);
    var request = given().basePath("");
    if (hasBody) request = request.contentType(JSON).body(body(operation, r));
    return request.request(method.toUpperCase(), url);
  }

  /** Every operation under /api/order/{token} in the contract, as {method, path, hasBody}. */
  @SuppressWarnings("unchecked")
  private static List<String[]> resellerOperations() {
    Map<String, Map<String, Object>> paths =
        given()
            .basePath("")
            .accept(JSON)
            .get("/q/openapi")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getMap("paths");
    var operations = new java.util.ArrayList<String[]>();
    for (var path : paths.entrySet()) {
      if (!path.getKey().startsWith("/api/order/{token}")) continue;
      for (String method : List.of("get", "post", "put", "patch", "delete"))
        if (path.getValue().get(method) instanceof Map<?, ?> operation)
          operations.add(
              new String[] {method, path.getKey(), String.valueOf(operation.containsKey("requestBody"))});
    }
    assertTrue(operations.size() >= 12, "Found only " + operations.size() + " reseller operations");
    return operations;
  }

  @Test
  void everyResellerOperationRefusesAnExpiredLinkWithNotFound() throws SQLException {
    var r = rows("link-walk-" + UUID.randomUUID());
    var operations = resellerOperations();

    for (var o : operations) {
      String name = o[0] + " " + o[1];
      int status = call(o[0], o[1], r, name, Boolean.parseBoolean(o[2])).statusCode();
      assertNotEquals(404, status, "A current link must reach " + name);
    }
    expire(r.token);

    for (var o : operations) {
      String name = o[0] + " " + o[1];
      var response = call(o[0], o[1], r, name, Boolean.parseBoolean(o[2]));
      assertEquals(404, response.statusCode(), "An expired link must not reach " + name);
    }
  }

  @Test
  void expiredBlockedUnknownAndMalformedLinksAnswerIdentically() throws SQLException {
    var r = rows("link-same-" + UUID.randomUUID());
    String expired = r.shop.benToken();
    String blocked = r.token;
    expire(expired);
    try (Connection c = source.getConnection()) {
      TestSql.execute(c, "UPDATE reseller SET status='blocked' WHERE token=?", blocked);
    }
    String unknown = DiscrepancyFixtures.token();

    for (String request : new String[] {"catalog", "place"}) {
      byte[] reference = null;
      String referenceType = null;
      for (String token : new String[] {expired, blocked, unknown, "short", "not a token at all!!"}) {
        Response response =
            request.equals("catalog")
                ? given().basePath("").get("/api/order/" + token.replace(" ", "%20"))
                : given()
                    .basePath("")
                    .contentType(JSON)
                    .body(place(r.shop.ham(), r.usedKey))
                    .post("/api/order/" + token.replace(" ", "%20") + "/orders");
        assertEquals(404, response.statusCode(), request + " " + token.length());
        if (reference == null) {
          reference = response.asByteArray();
          referenceType = response.contentType();
        }
        assertArrayEquals(reference, response.asByteArray(), request + " " + token.length());
        assertEquals(referenceType, response.contentType(), request + " " + token.length());
      }
      assertEquals("application/problem+json", referenceType);
    }
  }

  @Test
  void usingALinkDoesNotChangeItsExpiry() throws SQLException {
    var r = rows("link-use-" + UUID.randomUUID());
    Instant before = expiryOf(r.token);

    given().basePath("").get("/api/order/" + r.token).then().statusCode(200);
    given()
        .basePath("")
        .contentType(JSON)
        .body(place(r.shop.ham(), UUID.randomUUID()))
        .post("/api/order/" + r.token + "/orders")
        .then()
        .statusCode(201);

    assertEquals(before, expiryOf(r.token));
  }
}
