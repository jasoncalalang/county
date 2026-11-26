package ph.com.ridgewell.county.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Quote scope comes from the owner's identity or the reseller's link, never from the body: another
 * tenant's quote, or a quote cut for another reseller of the same business, is not found.
 */
@QuarkusTest
class QuoteIsolationTest {
  @Inject AgroalDataSource source;

  record Tenant(long id, long product, long reseller, String token, long other, String otherToken) {}

  static String token() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 22);
  }

  /** A business with one product and two resellers. */
  static Tenant tenant(Connection c, String subject) throws SQLException {
    long tenant = TestOwners.createBusiness(c, subject);
    long product =
        ConcurrentOrderingTest.insert(
            c,
            "INSERT INTO product(tenant_id,name,unit,price) VALUES (?,'Hamonado','pack',120)"
                + " RETURNING id",
            tenant);
    String token = token();
    long reseller =
        ConcurrentOrderingTest.insert(
            c,
            "INSERT INTO reseller(tenant_id,name,mobile,token) VALUES (?,'Rosa','+639171234567',?)"
                + " RETURNING id",
            tenant,
            token);
    String otherToken = token();
    long other =
        ConcurrentOrderingTest.insert(
            c,
            "INSERT INTO reseller(tenant_id,name,mobile,token) VALUES (?,'Ben','+639171234568',?)"
                + " RETURNING id",
            tenant,
            otherToken);
    return new Tenant(tenant, product, reseller, token, other, otherToken);
  }

  /** A quote for 2 of the product at 120, already sent to the tenant's first reseller. */
  static long sentQuote(Connection c, Tenant t, long number) throws SQLException {
    long quote =
        ConcurrentOrderingTest.insert(
            c,
            "INSERT INTO quote(tenant_id,reseller_id,number,created_at,expires_on,delivery_date,"
                + "status,total,sent_at) VALUES (?,?,?,now(),?,?,'sent',240,now()) RETURNING id",
            t.id(),
            t.reseller(),
            number,
            LocalDate.now().plusDays(7),
            LocalDate.now().plusDays(1));
    ConcurrentOrderingTest.insert(
        c,
        "INSERT INTO quote_line(tenant_id,quote_id,product_id,product_name,unit,quantity,price,"
            + "total) VALUES (?,?,?,'Hamonado','pack',2,120,240) RETURNING id",
        t.id(),
        quote,
        t.product());
    return quote;
  }

  private static String accept() {
    return "{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}";
  }

  @Test
  @TestSecurity(user = "quote-iso-b", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "quote-iso-b")})
  void anotherTenantsQuotesAndResellersAreNotFound() throws SQLException {
    Tenant a;
    long quote;
    Tenant b;
    try (Connection c = source.getConnection()) {
      a = tenant(c, "quote-iso-a");
      quote = sentQuote(c, a, 1);
      b = tenant(c, "quote-iso-b");
    }
    given().basePath("").get("/api/quotes/" + quote).then().statusCode(404);
    given().basePath("").post("/api/quotes/" + quote + "/send").then().statusCode(404);
    given().basePath("").post("/api/quotes/" + quote + "/cancel").then().statusCode(404);
    given().basePath("").get("/api/quotes").then().statusCode(200).body("$", empty());
    given()
        .basePath("")
        .get("/api/quotes?resellerId=" + a.reseller())
        .then()
        .statusCode(200)
        .body("$", empty());
    // The body's reseller id is looked up in the owner's tenant only.
    given()
        .basePath("")
        .contentType("application/json")
        .body(
            "{\"resellerId\":"
                + a.reseller()
                + ",\"lines\":[{\"productId\":"
                + b.product()
                + ",\"quantity\":1}],\"expiresOn\":\""
                + LocalDate.now().plusDays(3)
                + "\"}")
        .post("/api/quotes")
        .then()
        .statusCode(404);
    // Nor can a product from another tenant be quoted.
    given()
        .basePath("")
        .contentType("application/json")
        .body(
            "{\"resellerId\":"
                + b.reseller()
                + ",\"lines\":[{\"productId\":"
                + a.product()
                + ",\"quantity\":1}],\"expiresOn\":\""
                + LocalDate.now().plusDays(3)
                + "\"}")
        .post("/api/quotes")
        .then()
        .statusCode(404);
    // Tenant B's link cannot reach tenant A's quote.
    given().basePath("").get("/api/order/" + b.token() + "/quotes/" + quote).then().statusCode(404);
    given()
        .basePath("")
        .contentType("application/json")
        .body(accept())
        .post("/api/order/" + b.token() + "/quotes/" + quote + "/accept")
        .then()
        .statusCode(404);
  }

  @Test
  void aQuoteForOneResellerIsNotFoundOnAnotherResellersLink() throws SQLException {
    Tenant t;
    long quote;
    try (Connection c = source.getConnection()) {
      t = tenant(c, "quote-iso-reseller");
      quote = sentQuote(c, t, 1);
    }
    String other = "/api/order/" + t.otherToken() + "/quotes";
    given().basePath("").get(other).then().statusCode(200).body("$", empty());
    given().basePath("").get(other + "/" + quote).then().statusCode(404);
    given()
        .basePath("")
        .contentType("application/json")
        .body(accept())
        .post(other + "/" + quote + "/accept")
        .then()
        .statusCode(404);
    given().basePath("").post(other + "/" + quote + "/decline").then().statusCode(404);
    // Still open for the reseller it was cut for.
    given()
        .basePath("")
        .get("/api/order/" + t.token() + "/quotes/" + quote)
        .then()
        .statusCode(200)
        .body("status", is("sent"));
  }

  @Test
  void unknownAndMalformedLinksAreNotFound() {
    given().basePath("").get("/api/order/" + token() + "/quotes").then().statusCode(404);
    given().basePath("").get("/api/order/short/quotes/1").then().statusCode(404);
  }
}
