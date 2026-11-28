package ph.com.ridgewell.county.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ADR 0017: a quantity is above 0 and at most 9999 with up to three decimals, only kg takes a
 * fraction, and a line is price times quantity rounded half up to centavos.
 */
@QuarkusTest
class FractionalQuantityApiTest {
  /** Kg at 120, pack at 120, kg at 1.00, kg at 99.99 and kg at the price ceiling. */
  record Shop(long rice, long ham, long salt, long ube, long gold, String token) {}

  private static long product(String name, String unit, String price) {
    return given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"" + name + "\",\"unit\":\"" + unit + "\",\"price\":" + price + "}")
        .post("/api/products")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getLong("id");
  }

  private static Shop shop(String mobile) {
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"Nena\",\"cutoff\":\"16:00\",\"deliveryDays\":[1,2,3,4,5,6,7]}")
        .post("/api/tenants")
        .then()
        .statusCode(201);
    long rice = product("Rice", "kg", "120");
    long ham = product("Hamonado", "pack", "120");
    long salt = product("Salt", "kg", "1.00");
    long ube = product("Ube", "kg", "99.99");
    long gold = product("Gold", "kg", "9999999999.99");
    String token =
        given()
            .basePath("")
            .contentType("application/json")
            .body("{\"name\":\"Rosa\",\"mobile\":\"" + mobile + "\"}")
            .post("/api/resellers")
            .then()
            .statusCode(201)
            .extract()
            .path("token");
    return new Shop(rice, ham, salt, ube, gold, token);
  }

  private static String line(long product, String quantity) {
    return "{\"productId\":" + product + ",\"quantity\":" + quantity + "}";
  }

  private static Response place(Shop s, String... lines) {
    return given()
        .basePath("")
        .contentType("application/json")
        .body(
            "{\"lines\":["
                + String.join(",", lines)
                + "],\"idempotencyKey\":\""
                + UUID.randomUUID()
                + "\"}")
        .post("/api/order/" + s.token() + "/orders");
  }

  private static ValidatableResponse refused(Response response, String field) {
    return response
        .then()
        .statusCode(422)
        .contentType("application/problem+json")
        .body("errors", hasKey(field));
  }

  @Test
  @TestSecurity(user = "fraction-bounds", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "fraction-bounds")})
  void quantitiesOutsideTheRuleAreRefusedOnTheLine() {
    var s = shop("09171230101");
    for (String quantity :
        new String[] {"1.0005", "0", "-1", "9999.001", "10000", "0.0001", "\"abc\"", "null"})
      refused(place(s, line(s.rice(), quantity)), "lines[0].quantity");
    // The line that is wrong is named by its place in the request, not by product id order.
    refused(place(s, line(s.ham(), "10000"), line(s.rice(), "1")), "lines[0].quantity");
    refused(place(s, line(s.rice(), "1"), line(s.ham(), "0")), "lines[1].quantity");
  }

  @Test
  @TestSecurity(user = "fraction-exponent", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "fraction-exponent")})
  void hugeExponentsAreRefusedNotAnError() {
    var s = shop("09171230102");
    for (String quantity : new String[] {"1e400", "1e-400", "1E+999999", "-1e400"})
      refused(place(s, line(s.rice(), quantity)), "lines[0].quantity");
  }

  @Test
  @TestSecurity(user = "fraction-unit", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "fraction-unit")})
  void onlyKgTakesAFraction() {
    var s = shop("09171230103");
    refused(place(s, line(s.ham(), "1.5")), "lines[0].quantity");
    // The pack is the first line as sent although its product id is the higher one.
    refused(place(s, line(s.ham(), "0.5"), line(s.rice(), "1.5")), "lines[0].quantity");
    place(s, line(s.rice(), "1.5")).then().statusCode(201).body("lines[0].quantity", is(1.5f));
    place(s, line(s.ham(), "2.000"))
        .then()
        .statusCode(201)
        .body("lines[0].quantity", is(2))
        .body("total", is(240.0f));
  }

  @Test
  @TestSecurity(user = "fraction-tiny", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "fraction-tiny")})
  void aLineThatRoundsToNothingIsRefused() {
    var s = shop("09171230104");
    refused(place(s, line(s.salt(), "0.004")), "lines[0].quantity")
        .body("errors.'lines[0].quantity'", containsString("0.01"));
    refused(place(s, line(s.rice(), "1"), line(s.salt(), "0.001")), "lines[1].quantity");
    // Half a centavo rounds up, so 0.005 kg at 1.00 is the smallest line.
    place(s, line(s.salt(), "0.005"))
        .then()
        .statusCode(201)
        .body("total", is(0.01f))
        .body("lines[0].total", is(0.01f));
  }

  @Test
  @TestSecurity(user = "fraction-ceiling", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "fraction-ceiling")})
  void aLineOverTheCeilingIsRefusedNotAnError() {
    var s = shop("09171230105");
    refused(place(s, line(s.gold(), "1.5")), "lines[0].quantity");
    refused(place(s, line(s.gold(), "9999")), "lines[0].quantity");
  }

  @Test
  @TestSecurity(user = "fraction-totals", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "fraction-totals")})
  void linesRoundHalfUpAndTheOrderTotalIsTheSumOfTheLines() {
    var s = shop("09171230106");
    var order =
        place(s, line(s.rice(), "1.5"), line(s.ube(), "0.333"))
            .then()
            .statusCode(201)
            .body("lines.quantity", contains(1.5f, 0.333f))
            .body("lines.total", contains(180.0f, 33.3f))
            .body("total", is(213.3f))
            .extract()
            .jsonPath();
    long id = order.getLong("id");
    String date = order.getString("deliveryDate");
    given()
        .basePath("")
        .get("/api/orders/" + id)
        .then()
        .statusCode(200)
        .body("lines.quantity", contains(1.5f, 0.333f))
        .body("lines.total", contains(180.0f, 33.3f))
        .body("total", is(213.3f));
    given()
        .basePath("")
        .get("/api/orders?date=" + date)
        .then()
        .statusCode(200)
        .body("find { it.id == " + id + " }.items.quantity", contains(1.5f, 0.333f));
    given()
        .basePath("")
        .post("/api/orders/" + id + "/confirm")
        .then()
        .statusCode(200)
        .body("lines.quantity", contains(1.5f, 0.333f))
        .body("total", is(213.3f));
    given()
        .basePath("")
        .get("/api/production-run?date=" + date)
        .then()
        .statusCode(200)
        .body("lines.quantity", contains(1.5f, 0.333f));

    long second =
        place(s, line(s.rice(), "1.5")).then().statusCode(201).extract().jsonPath().getLong("id");
    given().basePath("").post("/api/orders/" + second + "/confirm").then().statusCode(200);
    given()
        .basePath("")
        .get("/api/production-run?date=" + date)
        .then()
        .statusCode(200)
        .body("lines.quantity", contains(3, 0.333f))
        .body("lines.orderCount", contains(2, 1));
  }

  /** Guard: this passes before the change too, because whole quantities already read as whole. */
  @Test
  @TestSecurity(user = "fraction-whole", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "fraction-whole")})
  void wholeQuantitiesStillReadAsWholeNumbers() {
    var s = shop("09171230107");
    var order =
        place(s, line(s.rice(), "10"), line(s.ham(), "2"))
            .then()
            .statusCode(201)
            .body("lines.quantity", contains(10, 2))
            .body("total", is(1440.0f))
            .extract()
            .jsonPath();
    long id = order.getLong("id");
    given()
        .basePath("")
        .get("/api/orders/" + id)
        .then()
        .body("lines.quantity", contains(10, 2));
    given()
        .basePath("")
        .get("/api/orders?date=" + order.getString("deliveryDate"))
        .then()
        .body("find { it.id == " + id + " }.items.quantity", contains(10, 2));
    given().basePath("").post("/api/orders/" + id + "/confirm").then().statusCode(200);
    given()
        .basePath("")
        .get("/api/production-run?date=" + order.getString("deliveryDate"))
        .then()
        .body("lines.quantity", contains(10, 2));
  }
}
