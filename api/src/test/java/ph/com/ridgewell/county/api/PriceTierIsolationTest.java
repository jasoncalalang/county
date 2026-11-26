package ph.com.ridgewell.county.api;

import static io.restassured.RestAssured.given;
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

/** Price-tier endpoints are tenant-scoped by the Google subject; nothing crosses a tenant. */
@QuarkusTest
class PriceTierIsolationTest {
  @Inject AgroalDataSource dataSource;

  record Fixture(long tenant, long product, long reseller, long tier) {}

  private Fixture fixture(String subject) throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      long tenant = TestOwners.createBusiness(c, subject);
      long product =
          ConcurrentOrderingTest.insert(
              c,
              "INSERT INTO product(tenant_id,name,unit,price) VALUES (?, 'Hamonado','pack',120)"
                  + " RETURNING id",
              tenant);
      String token = UUID.randomUUID().toString().replace("-", "").substring(0, 22);
      long reseller =
          ConcurrentOrderingTest.insert(
              c,
              "INSERT INTO reseller(tenant_id,name,mobile,token) VALUES (?, 'Rosa',"
                  + " '+639171234567',?) RETURNING id",
              tenant,
              token);
      long tier =
          ConcurrentOrderingTest.insert(
              c,
              "INSERT INTO price_tier(tenant_id,name) VALUES (?, 'Wholesale') RETURNING id",
              tenant);
      return new Fixture(tenant, product, reseller, tier);
    }
  }

  @Test
  @TestSecurity(
      user = "tier-isolation",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "tier-isolation")})
  void foreignTierProductAndResellerAreNotFound() throws SQLException {
    Fixture own = fixture("tier-isolation");
    Fixture other = fixture("tier-foreign-" + UUID.randomUUID());

    // Editing or pricing another tenant's tier or product is a 404.
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"Hijack\"}")
        .put("/api/price-tiers/" + other.tier)
        .then()
        .statusCode(404);
    given()
        .basePath("")
        .get("/api/price-tiers/" + other.tier + "/prices")
        .then()
        .statusCode(404);
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"price\":1}")
        .put("/api/products/" + other.product + "/tier-prices/" + other.tier)
        .then()
        .statusCode(404);
    // Own product but a foreign tier: still 404.
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"price\":1}")
        .put("/api/products/" + own.product + "/tier-prices/" + other.tier)
        .then()
        .statusCode(404);
    // Clearing a tier price across a tenant is a 404 either way round.
    given()
        .basePath("")
        .delete("/api/products/" + other.product + "/tier-prices/" + other.tier)
        .then()
        .statusCode(404);
    given()
        .basePath("")
        .delete("/api/products/" + own.product + "/tier-prices/" + other.tier)
        .then()
        .statusCode(404);
    given()
        .basePath("")
        .delete("/api/products/" + other.product + "/tier-prices/" + own.tier)
        .then()
        .statusCode(404);
    // Assigning any reseller to a foreign tier is a 404; a foreign reseller is a 404 too.
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"priceTierId\":" + other.tier + "}")
        .put("/api/resellers/" + own.reseller + "/price-tier")
        .then()
        .statusCode(404);
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"priceTierId\":" + own.tier + "}")
        .put("/api/resellers/" + other.reseller + "/price-tier")
        .then()
        .statusCode(404);

    // The listing shows only this tenant's tiers.
    given()
        .basePath("")
        .get("/api/price-tiers")
        .then()
        .statusCode(200)
        .body("id", org.hamcrest.Matchers.hasItem((int) own.tier))
        .body("id", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem((int) other.tier)));
  }

  @Test
  void tierEndpointsRequireAuthentication() {
    given().basePath("").get("/api/price-tiers").then().statusCode(401);
    given().basePath("").get("/api/price-tiers/1/prices").then().statusCode(401);
    given().basePath("").delete("/api/products/1/tier-prices/1").then().statusCode(401);
    for (String[] call :
        new String[][] {
          {"POST", "/api/price-tiers", "{\"name\":\"x\"}"},
          {"PUT", "/api/price-tiers/1", "{\"name\":\"x\"}"},
          {"PUT", "/api/products/1/tier-prices/1", "{\"price\":1}"},
          {"PUT", "/api/resellers/1/price-tier", "{\"priceTierId\":1}"}
        })
      given()
          .basePath("")
          .contentType("application/json")
          .body(call[2])
          .request(call[0], call[1])
          .then()
          .statusCode(401);
  }
}
