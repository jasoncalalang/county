package ph.com.ridgewell.county.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Every error is one Problem Details shape: type, title, status, detail and an errors map keyed by
 * the JSON path of the offending field. Decoding failures and validation failures agree.
 */
@QuarkusTest
class ErrorShapeApiTest {
  private static final String SUBJECT = "error-shape";
  @Inject AgroalDataSource source;

  record Fixture(long product, long inactive, String token) {}

  private Fixture fixture() throws SQLException {
    try (var c = source.getConnection()) {
      var existing =
          TestSql.rows(
              c,
              "SELECT b.id FROM business b JOIN external_identity i ON i.user_id = b.owner_user_id"
                  + " WHERE i.subject = ?",
              SUBJECT);
      long tenant =
          existing.isEmpty() ? TestOwners.createBusiness(c, SUBJECT) : TestSql.id(existing.get(0));
      long product =
          TestSql.id(
              TestSql.one(
                  c,
                  "INSERT INTO product(tenant_id,name,unit,price) VALUES (?,?,'pack',120)"
                      + " RETURNING id",
                  tenant,
                  "Active " + UUID.randomUUID()));
      long inactive =
          TestSql.id(
              TestSql.one(
                  c,
                  "INSERT INTO product(tenant_id,name,unit,price,active) VALUES"
                      + " (?,?,'pack',120,false) RETURNING id",
                  tenant,
                  "Gone " + UUID.randomUUID()));
      String token = UUID.randomUUID().toString().replace("-", "").substring(0, 22);
      TestSql.execute(
          c,
          "INSERT INTO reseller(tenant_id,name,mobile,token) VALUES (?,?,?,?)",
          tenant,
          "Rosa " + token,
          "+63917" + (1000000 + (int) (Math.random() * 8999999)),
          token);
      return new Fixture(product, inactive, token);
    }
  }

  private static ValidatableResponse problem(ValidatableResponse response, int status) {
    return response
        .statusCode(status)
        .contentType("application/problem+json")
        .body("type", is("about:blank"))
        .body("status", is(status))
        .body("title", not(emptyOrNullString()))
        .body("detail", not(emptyOrNullString()))
        .body("errors", instanceOf(java.util.Map.class));
  }

  private static ValidatableResponse post(String path, String body) {
    return given().basePath("").contentType("application/json").body(body).post(path).then();
  }

  private static ValidatableResponse put(String path, String body) {
    return given().basePath("").contentType("application/json").body(body).put(path).then();
  }

  @Test
  @TestSecurity(
      user = SUBJECT,
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = SUBJECT)})
  void malformedJsonIsABadRequestProblem() {
    problem(post("/api/products", "{\"name\":"), 400).body("errors.size()", is(0));
    problem(post("/api/products", "[1,2]"), 400).body("errors.size()", is(0));
  }

  @Test
  @TestSecurity(
      user = SUBJECT,
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = SUBJECT)})
  void wrongJsonTypesAreFieldErrors() throws SQLException {
    Fixture f = fixture();
    problem(post("/api/products", "{\"name\":\"X\",\"unit\":\"pack\",\"price\":\"abc\"}"), 422)
        .body("errors", hasKey("price"));
    problem(post("/api/products", "{\"name\":\"X\",\"unit\":\"pack\",\"price\":{}}"), 422)
        .body("errors", hasKey("price"));
    problem(
            put(
                "/api/settings",
                "{\"name\":\"X\",\"cutoff\":\"16:00\",\"deliveryDays\":[\"monday\"]}"),
            422)
        .body("errors", hasKey("deliveryDays"));
    problem(post("/api/orders/1/deliver", "{\"paid\":\"maybe\"}"), 422)
        .body("errors", hasKey("paid"));
    problem(
            post(
                "/api/order/" + f.token() + "/orders",
                "{\"lines\":[{\"productId\":"
                    + f.product()
                    + ",\"quantity\":\"ten\"}],\"idempotencyKey\":\""
                    + UUID.randomUUID()
                    + "\"}"),
            422)
        .body("errors", hasKey("lines[0].quantity"));
    problem(
            post(
                "/api/order/" + f.token() + "/orders",
                "{\"lines\":[{\"productId\":" + f.product() + ",\"quantity\":1}],"
                    + "\"idempotencyKey\":\"not-a-uuid\"}"),
            422)
        .body("errors", hasKey("idempotencyKey"));
  }

  @Test
  @TestSecurity(
      user = SUBJECT,
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = SUBJECT)})
  void validationErrorsUseTheSameFieldPaths() throws SQLException {
    Fixture f = fixture();
    problem(post("/api/products", "{\"name\":\"X\",\"unit\":\"pack\",\"price\":0}"), 422)
        .body("errors", hasKey("price"))
        .body("errors.keySet()", everyItem(not(containsString("."))));
    problem(post("/api/products", ""), 422).body("errors", hasKey("body"));
    problem(
            post(
                "/api/order/" + f.token() + "/orders",
                "{\"lines\":[{\"productId\":" + f.product() + ",\"quantity\":0}],"
                    + "\"idempotencyKey\":\""
                    + UUID.randomUUID()
                    + "\"}"),
            422)
        .body("errors", hasKey("lines[0].quantity"));
    problem(post("/api/resellers", "{\"name\":\"X\",\"mobile\":\"12\"}"), 422)
        .body("errors", hasKey("mobile"));
    problem(
            put("/api/settings", "{\"name\":\"X\",\"cutoff\":\"25:00\",\"deliveryDays\":[1]}"), 422)
        .body("errors", hasKey("cutoff"));
    problem(
            post(
                "/api/order/" + f.token() + "/orders",
                "{\"lines\":[{\"productId\":"
                    + f.product()
                    + ",\"quantity\":1},{\"productId\":"
                    + f.inactive()
                    + ",\"quantity\":1}],\"idempotencyKey\":\""
                    + UUID.randomUUID()
                    + "\"}"),
            422)
        .body("errors", hasKey("lines[1].productId"));
    problem(
            post(
                "/api/order/" + f.token() + "/orders",
                "{\"lines\":[{\"productId\":"
                    + f.product()
                    + ",\"quantity\":1},{\"productId\":"
                    + f.product()
                    + ",\"quantity\":2}],\"idempotencyKey\":\""
                    + UUID.randomUUID()
                    + "\"}"),
            422)
        .body("errors", hasKey("lines[1].productId"));
    problem(given().basePath("").queryParam("date", "tomorrow").get("/api/orders").then(), 422)
        .body("errors", hasKey("date"));
    problem(given().basePath("").queryParam("date", "").get("/api/orders").then(), 422)
        .body("errors", hasKey("date"));
    problem(given().basePath("").queryParam("status", "lost").get("/api/orders").then(), 422)
        .body("errors", hasKey("status"));
    problem(
            given().basePath("").queryParam("date", "2026-02-30").get("/api/production-run").then(),
            422)
        .body("errors", hasKey("date"));
    problem(given().basePath("").get("/api/production-run").then(), 422)
        .body("errors", hasKey("date"));
  }

  @Test
  @TestSecurity(
      user = SUBJECT,
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = SUBJECT)})
  void nonFieldProblemsCarryAnEmptyErrorsMap() throws SQLException {
    Fixture f = fixture();
    problem(given().basePath("").get("/api/orders/999999999").then(), 404)
        .body("errors.size()", is(0));
    problem(given().basePath("").get("/api/order/" + "x".repeat(22)).then(), 404)
        .body("errors.size()", is(0));
    problem(given().basePath("").get("/api/no-such-route").then(), 404)
        .body("errors.size()", is(0));
    String order =
        "{\"lines\":[{\"productId\":"
            + f.product()
            + ",\"quantity\":1}],\"idempotencyKey\":\""
            + UUID.randomUUID()
            + "\"}";
    int id = post("/api/order/" + f.token() + "/orders", order).statusCode(201).extract().path("id");
    problem(post("/api/orders/" + id + "/mark-paid", ""), 409).body("errors.size()", is(0));
  }
}
