package ph.com.ridgewell.county.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Quotes (build plan §3): create, send, accept into an order, decline, cancel and expiry. */
@QuarkusTest
class QuoteApiTest {
  private static final ZoneId MANILA = ZoneId.of("Asia/Manila");

  @Inject AgroalDataSource source;

  record Shop(long product, long other, long reseller, String token) {}

  /** A business for the signed-in subject, two products at 120 and 50, and one reseller. */
  private static Shop shop(String mobile) {
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"Nena\",\"cutoff\":\"16:00\",\"deliveryDays\":[1,2,3,4,5,6,7]}")
        .post("/api/tenants")
        .then()
        .statusCode(201);
    long product = product("Hamonado", 120);
    long other = product("Longganisa", 50);
    var reseller =
        given()
            .basePath("")
            .contentType("application/json")
            .body("{\"name\":\"Rosa\",\"mobile\":\"" + mobile + "\"}")
            .post("/api/resellers")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath();
    return new Shop(product, other, reseller.getLong("id"), reseller.getString("token"));
  }

  private static long product(String name, int price) {
    return given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"" + name + "\",\"unit\":\"pack\",\"price\":" + price + "}")
        .post("/api/products")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getLong("id");
  }

  private static String quoteBody(long reseller, String lines, LocalDate expiresOn) {
    return "{\"resellerId\":"
        + reseller
        + ",\"lines\":"
        + lines
        + ",\"note\":\"Holiday price\",\"expiresOn\":\""
        + expiresOn
        + "\"}";
  }

  private static String line(long product, int quantity) {
    return "{\"productId\":" + product + ",\"quantity\":" + quantity + "}";
  }

  private static LocalDate today() {
    return LocalDate.now(MANILA);
  }

  /** Creates and sends a quote for 10 of the shop's product; returns its id. */
  private static long sentQuote(Shop s) {
    long id = draft(s, "[" + line(s.product(), 10) + "]");
    given().basePath("").post("/api/quotes/" + id + "/send").then().statusCode(200);
    return id;
  }

  private static long draft(Shop s, String lines) {
    return given()
        .basePath("")
        .contentType("application/json")
        .body(quoteBody(s.reseller(), lines, today().plusDays(7)))
        .post("/api/quotes")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getLong("id");
  }

  private static io.restassured.response.Response accept(String token, long quote, UUID key) {
    return given()
        .basePath("")
        .contentType("application/json")
        .body("{\"idempotencyKey\":\"" + key + "\"}")
        .post("/api/order/" + token + "/quotes/" + quote + "/accept");
  }

  private int sql(String statement, Object... args) throws Exception {
    try (Connection c = source.getConnection()) {
      return TestSql.execute(c, statement, args);
    }
  }

  @Test
  @TestSecurity(user = "quote-loop", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "quote-loop")})
  void quoteIsSentAcceptedAndBecomesAnOrderAtTheQuotedPrices() {
    var s = shop("09171230001");
    JsonPath created =
        given()
            .basePath("")
            .contentType("application/json")
            .body(
                quoteBody(
                    s.reseller(),
                    "[" + line(s.other(), 3) + "," + line(s.product(), 10) + "]",
                    today().plusDays(7)))
            .post("/api/quotes")
            .then()
            .statusCode(201)
            .body("status", is("draft"))
            .body("number", is(1))
            .body("resellerName", is("Rosa"))
            .body("total", is(1350.0f))
            .body("note", is("Holiday price"))
            .body("lines.productName", contains("Hamonado", "Longganisa"))
            .body("lines.price", contains(120.0f, 50.0f))
            .body("sentAt", nullValue())
            .extract()
            .jsonPath();
    long id = created.getLong("id");
    // A draft is the owner's alone.
    given().basePath("").get("/api/order/" + s.token() + "/quotes/" + id).then().statusCode(404);
    given()
        .basePath("")
        .get("/api/order/" + s.token() + "/quotes")
        .then()
        .statusCode(200)
        .body("$", empty());

    given()
        .basePath("")
        .post("/api/quotes/" + id + "/send")
        .then()
        .statusCode(200)
        .body("status", is("sent"))
        .body("sentAt", notNullValue());
    given()
        .basePath("")
        .get("/api/order/" + s.token() + "/quotes")
        .then()
        .statusCode(200)
        .body("id", contains((int) id))
        .body("status", contains("sent"));
    given()
        .basePath("")
        .get("/api/order/" + s.token() + "/quotes/" + id)
        .then()
        .statusCode(200)
        .body("total", is(1350.0f))
        .body("lines.quantity", contains(10, 3));

    String catalogDate =
        given().basePath("").get("/api/order/" + s.token()).then().extract().path("deliveryDate");
    var order =
        accept(s.token(), id, UUID.randomUUID())
            .then()
            .statusCode(201)
            .body("number", is(1))
            .body("status", is("placed"))
            .body("total", is(1350.0f))
            .body("deliveryDate", is(catalogDate))
            .body("lines.productName", contains("Hamonado", "Longganisa"))
            .body("lines.price", contains(120.0f, 50.0f))
            .body("events", empty())
            .extract()
            .jsonPath();
    long orderId = order.getLong("id");
    given()
        .basePath("")
        .get("/api/orders/" + orderId)
        .then()
        .statusCode(200)
        .body("total", is(1350.0f))
        .body("resellerName", is("Rosa"));
    given()
        .basePath("")
        .get("/api/quotes/" + id)
        .then()
        .statusCode(200)
        .body("status", is("accepted"))
        .body("acceptedOrderId", is((int) orderId))
        .body("acceptedAt", notNullValue());
    // Accepted quotes leave the reseller's open list but stay readable on the link.
    given()
        .basePath("")
        .get("/api/order/" + s.token() + "/quotes")
        .then()
        .body("$", empty());
    given()
        .basePath("")
        .get("/api/order/" + s.token() + "/quotes/" + id)
        .then()
        .statusCode(200)
        .body("status", is("accepted"));
    // Accepting marks the reseller as having ordered, as placing does.
    given()
        .basePath("")
        .get("/api/resellers")
        .then()
        .body("find { it.id == " + s.reseller() + " }.status", is("active"));
  }

  @Test
  @TestSecurity(user = "quote-replay", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "quote-replay")})
  void acceptingTwiceReturnsTheSameOrder() {
    var s = shop("09171230002");
    long id = sentQuote(s);
    UUID key = UUID.randomUUID();
    long order = accept(s.token(), id, key).then().statusCode(201).extract().jsonPath().getLong("id");
    accept(s.token(), id, key).then().statusCode(200).body("id", is((int) order));
    // A new key on an accepted quote still returns the order it placed, never a second one.
    accept(s.token(), id, UUID.randomUUID()).then().statusCode(200).body("id", is((int) order));
    given()
        .basePath("")
        .get("/api/orders")
        .then()
        .statusCode(200)
        .body("$", hasSize(1));
  }

  @Test
  @TestSecurity(user = "quote-replay-events", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "quote-replay-events")})
  void replayedAcceptanceShowsTheResellersViewOfTheOrderHistory() throws Exception {
    var s = shop("09171230012");
    sql(
        "UPDATE app_user SET display_name='Nena Santos', email='nena.santos@example.com'"
            + " WHERE id=(SELECT user_id FROM external_identity WHERE provider='google'"
            + " AND subject=?)",
        "quote-replay-events");
    long id = sentQuote(s);
    long order =
        accept(s.token(), id, UUID.randomUUID())
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getLong("id");
    given().basePath("").post("/api/orders/" + order + "/confirm").then().statusCode(200);

    var replay = accept(s.token(), id, UUID.randomUUID());
    replay.then().statusCode(200);
    org.junit.jupiter.api.Assertions.assertEquals(
        java.util.List.of("confirmed"), replay.jsonPath().getList("events.toStatus"));
    org.junit.jupiter.api.Assertions.assertEquals(
        java.util.List.of("Nena"), replay.jsonPath().getList("events.actorName"));
    String body = replay.asString();
    for (String forbidden :
        new String[] {
          "Nena Santos", "nena.santos@example.com", "actorUserId", "actorResellerId"
        })
      org.junit.jupiter.api.Assertions.assertFalse(body.contains(forbidden), body);
  }

  @Test
  @TestSecurity(user = "quote-key", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "quote-key")})
  void aKeyAlreadySpentOnAnOrderIsRefused() {
    var s = shop("09171230003");
    long id = sentQuote(s);
    UUID key = UUID.randomUUID();
    given()
        .basePath("")
        .contentType("application/json")
        .body(ConcurrentOrderingTest.body(s.product(), 1, key))
        .post("/api/order/" + s.token() + "/orders")
        .then()
        .statusCode(201);
    accept(s.token(), id, key).then().statusCode(409);
    given().basePath("").get("/api/quotes/" + id).then().body("status", is("sent"));
  }

  @Test
  @TestSecurity(user = "quote-terminal", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "quote-terminal")})
  void declinedCancelledAndExpiredQuotesRefuseAcceptance() throws Exception {
    var s = shop("09171230004");
    long declined = sentQuote(s);
    given()
        .basePath("")
        .post("/api/order/" + s.token() + "/quotes/" + declined + "/decline")
        .then()
        .statusCode(200)
        .body("status", is("declined"))
        .body("declinedAt", notNullValue());
    accept(s.token(), declined, UUID.randomUUID())
        .then()
        .statusCode(409)
        .body("detail", is("Quote is now declined"));
    given()
        .basePath("")
        .post("/api/quotes/" + declined + "/cancel")
        .then()
        .statusCode(409);

    long cancelled = sentQuote(s);
    given()
        .basePath("")
        .post("/api/quotes/" + cancelled + "/cancel")
        .then()
        .statusCode(200)
        .body("status", is("cancelled"));
    accept(s.token(), cancelled, UUID.randomUUID())
        .then()
        .statusCode(409)
        .body("detail", is("Quote is now cancelled"));
    given()
        .basePath("")
        .post("/api/order/" + s.token() + "/quotes/" + cancelled + "/decline")
        .then()
        .statusCode(409);

    long expired = sentQuote(s);
    // Yesterday was its last day.
    sql("UPDATE quote SET expires_on=? WHERE id=?", today().minusDays(1), expired);
    given().basePath("").get("/api/quotes/" + expired).then().body("status", is("expired"));
    accept(s.token(), expired, UUID.randomUUID())
        .then()
        .statusCode(409)
        .body("detail", is("Quote is now expired"));
    given()
        .basePath("")
        .post("/api/order/" + s.token() + "/quotes/" + expired + "/decline")
        .then()
        .statusCode(409);
    given()
        .basePath("")
        .get("/api/order/" + s.token() + "/quotes")
        .then()
        .body("$", empty());
    given()
        .basePath("")
        .get("/api/quotes?status=expired")
        .then()
        .body("id", contains((int) expired));
    given()
        .basePath("")
        .get("/api/quotes?status=sent")
        .then()
        .body("$", empty());
    given()
        .basePath("")
        .get("/api/orders")
        .then()
        .body("$", empty());
  }

  @Test
  @TestSecurity(user = "quote-last-day", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "quote-last-day")})
  void aQuoteCanBeAcceptedOnItsLastDay() throws Exception {
    var s = shop("09171230005");
    long id = sentQuote(s);
    sql("UPDATE quote SET expires_on=? WHERE id=?", today(), id);
    accept(s.token(), id, UUID.randomUUID()).then().statusCode(201);
  }

  @Test
  @TestSecurity(user = "quote-expired-draft", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "quote-expired-draft")})
  void aDraftPastItsLastDayCannotBeSentButCanBeCancelled() throws Exception {
    var s = shop("09171230006");
    long id = draft(s, "[" + line(s.product(), 1) + "]");
    sql("UPDATE quote SET expires_on=? WHERE id=?", today().minusDays(1), id);
    given().basePath("").post("/api/quotes/" + id + "/send").then().statusCode(409);
    given().basePath("").get("/api/quotes/" + id).then().body("status", is("draft"));
    given()
        .basePath("")
        .post("/api/quotes/" + id + "/cancel")
        .then()
        .statusCode(200)
        .body("status", is("cancelled"));
  }

  @Test
  @TestSecurity(user = "quote-frozen", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "quote-frozen")})
  void acceptanceKeepsQuotedPricesAfterPriceChangesAndDeactivation() {
    var s = shop("09171230007");
    long id = draft(s, "[" + line(s.product(), 2) + "," + line(s.other(), 1) + "]");
    given().basePath("").post("/api/quotes/" + id + "/send").then().statusCode(200);
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"Hamonado\",\"unit\":\"pack\",\"price\":200}")
        .put("/api/products/" + s.product())
        .then()
        .statusCode(200);
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"Longganisa\",\"unit\":\"pack\",\"price\":50,\"active\":false}")
        .put("/api/products/" + s.other())
        .then()
        .statusCode(200);
    given()
        .basePath("")
        .get("/api/quotes/" + id)
        .then()
        .body("lines.price", contains(120.0f, 50.0f))
        .body("total", is(290.0f));
    accept(s.token(), id, UUID.randomUUID())
        .then()
        .statusCode(201)
        .body("lines.price", contains(120.0f, 50.0f))
        .body("lines.productName", contains("Hamonado", "Longganisa"))
        .body("total", is(290.0f));
  }

  @Test
  @TestSecurity(user = "quote-tier", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "quote-tier")})
  void aQuoteCapturesTheResellersTierPrice() {
    var s = shop("09171230008");
    long tier =
        given()
            .basePath("")
            .contentType("application/json")
            .body("{\"name\":\"Wholesale\"}")
            .post("/api/price-tiers")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getLong("id");
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"price\":100}")
        .put("/api/products/" + s.product() + "/tier-prices/" + tier)
        .then()
        .statusCode(200);
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"priceTierId\":" + tier + "}")
        .put("/api/resellers/" + s.reseller() + "/price-tier")
        .then()
        .statusCode(200);
    given()
        .basePath("")
        .contentType("application/json")
        .body(
            quoteBody(
                s.reseller(),
                "[" + line(s.product(), 2) + "," + line(s.other(), 1) + "]",
                today()))
        .post("/api/quotes")
        .then()
        .statusCode(201)
        .body("lines.price", contains(100.0f, 50.0f))
        .body("total", is(250.0f));
  }

  @Test
  @TestSecurity(user = "quote-delivery", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "quote-delivery")})
  void theAcceptedOrderGetsTheDeliveryDateOfAnOrderPlacedNow() throws Exception {
    var s = shop("09171230009");
    long id = sentQuote(s);
    // The estimate shown on the quote is from creation; pretend it was made long ago.
    LocalDate estimate = today().minusDays(20);
    sql("UPDATE quote SET delivery_date=? WHERE id=?", estimate, id);
    String now =
        given().basePath("").get("/api/order/" + s.token()).then().extract().path("deliveryDate");
    accept(s.token(), id, UUID.randomUUID())
        .then()
        .statusCode(201)
        .body("deliveryDate", is(now));
    given()
        .basePath("")
        .get("/api/quotes/" + id)
        .then()
        .body("deliveryDate", is(estimate.toString()));
  }

  @Test
  @TestSecurity(user = "quote-numbers", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "quote-numbers")})
  void quoteNumbersArePerBusinessGapFreeAndSeparateFromOrders() throws Exception {
    var s = shop("09171230010");
    long first = draft(s, "[" + line(s.product(), 1) + "]");
    given().basePath("").post("/api/quotes/" + first + "/cancel").then().statusCode(200);
    // A refused quote consumes no number.
    given()
        .basePath("")
        .contentType("application/json")
        .body(quoteBody(s.reseller(), "[" + line(s.product(), 1) + "," + line(s.product(), 2) + "]", today()))
        .post("/api/quotes")
        .then()
        .statusCode(422)
        .body("errors.'lines[1].productId'", notNullValue());
    long second = draft(s, "[" + line(s.product(), 1) + "]");
    given().basePath("").get("/api/quotes/" + second).then().body("number", is(2));
    given()
        .basePath("")
        .get("/api/quotes")
        .then()
        .body("number", contains(2, 1))
        .body("status", contains("draft", "cancelled"));
    given()
        .basePath("")
        .get("/api/quotes?status=cancelled")
        .then()
        .body("id", contains((int) first));
    given()
        .basePath("")
        .get("/api/quotes?resellerId=" + s.reseller())
        .then()
        .body("$", hasSize(2));
    given()
        .basePath("")
        .get("/api/quotes?resellerId=" + (s.reseller() + 100000))
        .then()
        .body("$", empty());
    given().basePath("").get("/api/quotes?status=open").then().statusCode(422);
    given().basePath("").get("/api/quotes?resellerId=x").then().statusCode(422);
    // The first order is #1, whatever the quote count.
    given()
        .basePath("")
        .contentType("application/json")
        .body(ConcurrentOrderingTest.body(s.product(), 1, UUID.randomUUID()))
        .post("/api/order/" + s.token() + "/orders")
        .then()
        .statusCode(201)
        .body("number", is(1));
  }

  @Test
  @TestSecurity(user = "quote-validation", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "quote-validation")})
  void invalidQuotesAreRefused() throws Exception {
    var s = shop("09171230011");
    given()
        .basePath("")
        .contentType("application/json")
        .body(quoteBody(s.reseller(), "[" + line(s.product(), 1) + "]", today().minusDays(1)))
        .post("/api/quotes")
        .then()
        .statusCode(422)
        .body("errors.expiresOn", notNullValue());
    given()
        .basePath("")
        .contentType("application/json")
        .body(quoteBody(s.reseller(), "[]", today()))
        .post("/api/quotes")
        .then()
        .statusCode(422)
        .body("errors.lines", notNullValue());
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"resellerId\":" + s.reseller() + ",\"lines\":[" + line(s.product(), 1) + "]}")
        .post("/api/quotes")
        .then()
        .statusCode(422)
        .body("errors.expiresOn", notNullValue());
    given()
        .basePath("")
        .contentType("application/json")
        .body(quoteBody(s.reseller(), "[" + line(s.product(), 1) + "]", today()).replace(today().toString(), "soon"))
        .post("/api/quotes")
        .then()
        .statusCode(422)
        .body("errors.expiresOn", notNullValue());
    given()
        .basePath("")
        .contentType("application/json")
        .body(quoteBody(s.reseller(), "[" + line(s.product(), 0) + "]", today()))
        .post("/api/quotes")
        .then()
        .statusCode(422)
        .body("errors.'lines[0].quantity'", notNullValue());
    // Server-owned fields in the body are ignored, not obeyed.
    given()
        .basePath("")
        .contentType("application/json")
        .body(
            "{\"resellerId\":"
                + s.reseller()
                + ",\"lines\":[{\"productId\":"
                + s.product()
                + ",\"quantity\":1,\"price\":1}],\"expiresOn\":\""
                + today()
                + "\",\"status\":\"sent\",\"number\":99,\"total\":1}")
        .post("/api/quotes")
        .then()
        .statusCode(201)
        .body("status", is("draft"))
        .body("number", is(1))
        .body("lines[0].price", is(120.0f))
        .body("total", is(120.0f));
    sql("UPDATE product SET active=false WHERE id=?", s.other());
    given()
        .basePath("")
        .contentType("application/json")
        .body(quoteBody(s.reseller(), "[" + line(s.other(), 1) + "]", today()))
        .post("/api/quotes")
        .then()
        .statusCode(422)
        .body("errors.'lines[0].productId'", is("Longganisa is no longer available"));
    given()
        .basePath("")
        .post("/api/resellers/" + s.reseller() + "/block")
        .then()
        .statusCode(200);
    given()
        .basePath("")
        .contentType("application/json")
        .body(quoteBody(s.reseller(), "[" + line(s.product(), 1) + "]", today()))
        .post("/api/quotes")
        .then()
        .statusCode(422)
        .body("errors.resellerId", notNullValue());
  }

  @Test
  @TestSecurity(user = "quote-blocked", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "quote-blocked")})
  void aBlockedLinkSeesNoQuotes() {
    var s = shop("09171230012");
    long id = sentQuote(s);
    given()
        .basePath("")
        .post("/api/resellers/" + s.reseller() + "/block")
        .then()
        .statusCode(200);
    given().basePath("").get("/api/order/" + s.token() + "/quotes").then().statusCode(404);
    given().basePath("").get("/api/order/" + s.token() + "/quotes/" + id).then().statusCode(404);
    accept(s.token(), id, UUID.randomUUID()).then().statusCode(404);
    given()
        .basePath("")
        .post("/api/order/" + s.token() + "/quotes/" + id + "/decline")
        .then()
        .statusCode(404);
    given().basePath("").get("/api/quotes/" + id).then().body("status", is("sent"));
  }

  @Test
  void quoteEndpointsRequireAuthentication() {
    given().basePath("").get("/api/quotes").then().statusCode(401);
    given().basePath("").contentType("application/json").body("{}").post("/api/quotes").then().statusCode(401);
  }
}
