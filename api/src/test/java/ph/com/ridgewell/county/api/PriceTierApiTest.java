package ph.com.ridgewell.county.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Per-reseller price tiers (Beyond-v1, from the Itemcount reference). A reseller assigned to a tier
 * sees and is charged the tier's per-product price; everyone else keeps the base price. Captured
 * prices stay immutable after placement (ADR 0012).
 */
@QuarkusTest
class PriceTierApiTest {

  private static void bigEquals(String expected, String actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual)), actual);
  }

  private String token(int resellerId, String name, String mobile) {
    return given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"" + name + "\",\"mobile\":\"" + mobile + "\"}")
        .post("/api/resellers")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getString("token");
  }

  private String catalogPrice(String token, int product) {
    return given()
        .basePath("")
        .get("/api/order/" + token)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getString("products.find{it.id==" + product + "}.price");
  }

  private io.restassured.path.json.JsonPath place(String token, int product, int qty, String key) {
    return given()
        .basePath("")
        .contentType("application/json")
        .body(
            "{\"lines\":[{\"productId\":"
                + product
                + ",\"quantity\":"
                + qty
                + "}],\"idempotencyKey\":\""
                + key
                + "\"}")
        .post("/api/order/" + token + "/orders")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath();
  }

  @Test
  @TestSecurity(
      user = "tier-owner",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "tier-owner")})
  void tierPriceIsChargedToAssignedResellerOnlyAndStaysImmutableAfterPlacement() {
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"Nena\",\"cutoff\":\"16:00\",\"deliveryDays\":[1,2,3,4,5,6,7]}")
        .post("/api/tenants")
        .then()
        .statusCode(201);
    int product =
        given()
            .basePath("")
            .contentType("application/json")
            .body("{\"name\":\"Hamonado\",\"unit\":\"pack\",\"price\":120}")
            .post("/api/products")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    // A new tier is created active and listed.
    int tier =
        given()
            .basePath("")
            .contentType("application/json")
            .body("{\"name\":\"Wholesale A\"}")
            .post("/api/price-tiers")
            .then()
            .statusCode(201)
            .body("name", org.hamcrest.Matchers.is("Wholesale A"))
            .body("active", org.hamcrest.Matchers.is(true))
            .extract()
            .path("id");
    given()
        .basePath("")
        .get("/api/price-tiers")
        .then()
        .statusCode(200)
        .body("id", org.hamcrest.Matchers.hasItem(tier));

    // Set the tier price below the base price.
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"price\":100}")
        .put("/api/products/" + product + "/tier-prices/" + tier)
        .then()
        .statusCode(200);
    var prices =
        given()
            .basePath("")
            .get("/api/price-tiers/" + tier + "/prices")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    bigEquals("120.00", prices.getString("products.find{it.productId==" + product + "}.basePrice"));
    bigEquals("100.00", prices.getString("products.find{it.productId==" + product + "}.tierPrice"));

    String rosa = token(0, "Rosa", "09171234567");
    String ben = token(0, "Ben", "09171234568");

    // Assign only Rosa to the tier.
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"priceTierId\":" + tier + "}")
        .put("/api/resellers/" + rosaId(rosa) + "/price-tier")
        .then()
        .statusCode(200)
        .body("priceTierId", org.hamcrest.Matchers.is(tier));

    // Rosa sees the tier price; Ben keeps the base price.
    bigEquals("100.00", catalogPrice(rosa, product));
    bigEquals("120.00", catalogPrice(ben, product));

    // Placement captures each reseller's effective price.
    var rosaOrder = place(rosa, product, 2, UUID.randomUUID().toString());
    bigEquals("100.00", rosaOrder.getString("lines[0].price"));
    bigEquals("200.00", rosaOrder.getString("total"));
    long rosaOrderId = rosaOrder.getLong("id");
    var benOrder = place(ben, product, 1, UUID.randomUUID().toString());
    bigEquals("120.00", benOrder.getString("lines[0].price"));

    // Change the tier price. Rosa's already-placed order is untouched (ADR 0012);
    // a new order gets the new price.
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"price\":90}")
        .put("/api/products/" + product + "/tier-prices/" + tier)
        .then()
        .statusCode(200);
    bigEquals(
        "100.00",
        given()
            .basePath("")
            .get("/api/order/" + rosa + "/orders/" + rosaOrderId)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("lines[0].price"));
    bigEquals("90.00", catalogPrice(rosa, product));
    bigEquals("90.00", place(rosa, product, 1, UUID.randomUUID().toString()).getString("lines[0].price"));

    // Clearing the override reverts Rosa to the base price.
    given()
        .basePath("")
        .delete("/api/products/" + product + "/tier-prices/" + tier)
        .then()
        .statusCode(204);
    bigEquals("120.00", catalogPrice(rosa, product));

    // Clearing Rosa's tier assignment also reverts her to the base price.
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"priceTierId\":null}")
        .put("/api/resellers/" + rosaId(rosa) + "/price-tier")
        .then()
        .statusCode(200)
        .body("priceTierId", org.hamcrest.Matchers.nullValue());
    bigEquals("120.00", catalogPrice(rosa, product));
  }

  // The reseller id is not exposed on the token page; look it up from the owner's list by token.
  private int rosaId(String token) {
    return given()
        .basePath("")
        .get("/api/resellers")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getInt("find{it.token=='" + token + "'}.id");
  }

  @Test
  @TestSecurity(
      user = "tier-validation",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "tier-validation")})
  void tierPricesAndNamesAreValidated() {
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"Nena\",\"cutoff\":\"16:00\",\"deliveryDays\":[1,2,3,4,5,6,7]}")
        .post("/api/tenants")
        .then()
        .statusCode(201);
    int product =
        given()
            .basePath("")
            .contentType("application/json")
            .body("{\"name\":\"Hamonado\",\"unit\":\"pack\",\"price\":120}")
            .post("/api/products")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    int tier =
        given()
            .basePath("")
            .contentType("application/json")
            .body("{\"name\":\"Wholesale\"}")
            .post("/api/price-tiers")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    // A blank tier name is rejected.
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"\"}")
        .post("/api/price-tiers")
        .then()
        .statusCode(422);
    // Non-positive and sub-centavo tier prices are rejected.
    for (String price : new String[] {"0", "-1", "0.001"})
      given()
          .basePath("")
          .contentType("application/json")
          .body("{\"price\":" + price + "}")
          .put("/api/products/" + product + "/tier-prices/" + tier)
          .then()
          .statusCode(422);
    // A duplicate active tier name collides, on create and on rename.
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"Wholesale\"}")
        .post("/api/price-tiers")
        .then()
        .statusCode(409);
    int other =
        given()
            .basePath("")
            .contentType("application/json")
            .body("{\"name\":\"Retail\"}")
            .post("/api/price-tiers")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"wholesale\"}")
        .put("/api/price-tiers/" + other)
        .then()
        .statusCode(409);
    // A tier id must be positive.
    String reseller = token(0, "Rosa", "09171234567");
    for (String id : new String[] {"0", "-1"})
      given()
          .basePath("")
          .contentType("application/json")
          .body("{\"priceTierId\":" + id + "}")
          .put("/api/resellers/" + rosaId(reseller) + "/price-tier")
          .then()
          .statusCode(422);
  }

  @Test
  @TestSecurity(
      user = "tier-retirement",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "tier-retirement")})
  void deactivatedTierCannotBeAssignedButKeepsPricingResellersAlreadyOnIt() {
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"Nena\",\"cutoff\":\"16:00\",\"deliveryDays\":[1,2,3,4,5,6,7]}")
        .post("/api/tenants")
        .then()
        .statusCode(201);
    int product =
        given()
            .basePath("")
            .contentType("application/json")
            .body("{\"name\":\"Hamonado\",\"unit\":\"pack\",\"price\":120}")
            .post("/api/products")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    int tier =
        given()
            .basePath("")
            .contentType("application/json")
            .body("{\"name\":\"Wholesale\"}")
            .post("/api/price-tiers")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"price\":100}")
        .put("/api/products/" + product + "/tier-prices/" + tier)
        .then()
        .statusCode(200);
    String rosa = token(0, "Rosa", "09171234567");
    String ben = token(0, "Ben", "09171234568");
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"priceTierId\":" + tier + "}")
        .put("/api/resellers/" + rosaId(rosa) + "/price-tier")
        .then()
        .statusCode(200);

    // Rename and deactivate in one edit; the tier lists after the active ones.
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"Wholesale 2026\",\"active\":false}")
        .put("/api/price-tiers/" + tier)
        .then()
        .statusCode(200)
        .body("name", org.hamcrest.Matchers.is("Wholesale 2026"))
        .body("active", org.hamcrest.Matchers.is(false));
    int replacement =
        given()
            .basePath("")
            .contentType("application/json")
            .body("{\"name\":\"Wholesale 2026\"}")
            .post("/api/price-tiers")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    given()
        .basePath("")
        .get("/api/price-tiers")
        .then()
        .statusCode(200)
        .body("id", org.hamcrest.Matchers.contains(replacement, tier));

    // An inactive tier cannot be assigned.
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"priceTierId\":" + tier + "}")
        .put("/api/resellers/" + rosaId(ben) + "/price-tier")
        .then()
        .statusCode(422);

    // A reseller already on it keeps its prices until reassigned.
    bigEquals("100.00", catalogPrice(rosa, product));
    bigEquals(
        "100.00",
        place(rosa, product, 1, UUID.randomUUID().toString()).getString("lines[0].price"));
    bigEquals("120.00", catalogPrice(ben, product));

    // It cannot be reactivated under a name an active tier now holds.
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"Wholesale 2026\",\"active\":true}")
        .put("/api/price-tiers/" + tier)
        .then()
        .statusCode(409);
  }
}
