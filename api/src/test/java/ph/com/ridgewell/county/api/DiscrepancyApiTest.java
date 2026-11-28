package ph.com.ridgewell.county.api;

import static io.restassured.RestAssured.given;
import static ph.com.ridgewell.county.api.DiscrepancyFixtures.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Short-delivery reports (ADR 0012): filing against a delivered order and reading the report. The
 * order never changes; the report captures what was ordered, at what price, and what arrived.
 */
@QuarkusTest
class DiscrepancyApiTest {
  @Inject AgroalDataSource source;

  private Shop shop(String subject) throws SQLException {
    try (var c = source.getConnection()) {
      return DiscrepancyFixtures.shop(c, subject);
    }
  }

  private Order deliveredHam(Shop s, long reseller) throws SQLException {
    try (var c = source.getConnection()) {
      return delivered(c, s, reseller, ham(s, "10"));
    }
  }

  private long reports(Shop s, Order o) throws SQLException {
    try (var c = source.getConnection()) {
      return DiscrepancyFixtures.reports(c, s.tenant(), o.id());
    }
  }

  private static void refused(Response response, String field, String message) {
    response
        .then()
        .statusCode(422)
        .contentType("application/problem+json")
        .body("errors", hasKey(field))
        .body("errors.'" + field + "'", is(message));
  }

  private static void refused(Response response, String field) {
    response
        .then()
        .statusCode(422)
        .contentType("application/problem+json")
        .body("errors", hasKey(field));
  }

  @Test
  @TestSecurity(
      user = "disc-file",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "disc-file")})
  void aResellerFilesAShortLineAndBothPartiesReadTheSameReport() throws SQLException {
    Shop s = shop("disc-file");
    Order o = deliveredHam(s, s.rosa());
    Instant before = Instant.now().minusSeconds(5);

    var filed =
        fileAsReseller(
                s.rosaToken(),
                o.id(),
                body(UUID.randomUUID(), "Two packs were crushed", line(s.ham(), "8")))
            .then()
            .statusCode(201)
            .body("id", greaterThan(0))
            .body("orderId", is((int) o.id()))
            .body("orderNumber", is((int) o.number()))
            .body("filedBy", is("reseller"))
            .body("status", is("open"))
            .body("note", is("Two packs were crushed"))
            .body("resolutionNote", is(""))
            .body("resolvedBy", nullValue())
            .body("resolvedAt", nullValue())
            .body("shortfallTotal", is(240.0f))
            .body("lines", hasSize(1))
            .body("lines[0].productId", is((int) s.ham()))
            .body("lines[0].productName", is("Hamonado"))
            .body("lines[0].unit", is("pack"))
            .body("lines[0].orderedQuantity", is(10))
            .body("lines[0].receivedQuantity", is(8))
            .body("lines[0].price", is(120.0f))
            .body("lines[0].shortfallTotal", is(240.0f))
            .extract()
            .response();
    assertFalse(Instant.parse(filed.path("filedAt")).isBefore(before));

    var asReseller = readAsReseller(s.rosaToken(), o.id());
    var asOwner = readAsOwner(o.id());
    asReseller.then().statusCode(200);
    asOwner.then().statusCode(200);
    assertEquals(filed.jsonPath().getMap("$"), asReseller.jsonPath().getMap("$"));
    assertEquals(filed.jsonPath().getMap("$"), asOwner.jsonPath().getMap("$"));
    assertEquals(1, reports(s, o));
  }

  @Test
  @TestSecurity(
      user = "disc-owner-file",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "disc-owner-file")})
  void anOwnerFilesAndOnlyTheDifferingLinesAreKeptWithDecimalQuantities() throws SQLException {
    Shop s = shop("disc-owner-file");
    Order o;
    try (var c = source.getConnection()) {
      o = delivered(c, s, s.rosa(), ham(s, "10"), rice(s, "1.5"));
    }

    var filed =
        fileAsOwner(o.id(), body(line(s.rice(), "1.25")))
            .then()
            .statusCode(201)
            .body("filedBy", is("owner"))
            .body("note", is(""))
            .body("lines", hasSize(1))
            .body("lines[0].productName", is("Rice"))
            .body("lines[0].unit", is("kg"))
            .body("lines[0].orderedQuantity", is(1.5f))
            .body("lines[0].receivedQuantity", is(1.25f))
            .body("lines[0].price", is(99.99f))
            // 0.25 x 99.99 = 24.9975, rounded half up
            .body("lines[0].shortfallTotal", is(25.0f))
            .body("shortfallTotal", is(25.0f))
            .extract()
            .response();

    assertEquals(
        filed.jsonPath().getMap("$"), readAsReseller(s.rosaToken(), o.id()).jsonPath().getMap("$"));
  }

  @Test
  @TestSecurity(
      user = "disc-totals",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "disc-totals")})
  void theReportTotalIsTheSumOfItsLinesAndOverDeliveryAndNothingReceivedAreFiled()
      throws SQLException {
    Shop s = shop("disc-totals");
    Order o;
    Order over;
    try (var c = source.getConnection()) {
      o = delivered(c, s, s.rosa(), ham(s, "10"), rice(s, "1.5"));
      over = delivered(c, s, s.rosa(), ham(s, "10"));
    }

    fileAsReseller(s.rosaToken(), o.id(), body(line(s.ham(), "0"), line(s.rice(), "1.25")))
        .then()
        .statusCode(201)
        .body("lines.receivedQuantity", contains(0, 1.25f))
        .body("lines.shortfallTotal", contains(1200.0f, 25.0f))
        .body("shortfallTotal", is(1225.0f));

    // More than was ordered is accepted, and the shortfall is negative.
    fileAsReseller(s.rosaToken(), over.id(), body(line(s.ham(), "12")))
        .then()
        .statusCode(201)
        .body("lines[0].receivedQuantity", is(12))
        .body("lines[0].shortfallTotal", is(-240.0f))
        .body("shortfallTotal", is(-240.0f));
  }

  @Test
  @TestSecurity(
      user = "disc-unchanged",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "disc-unchanged")})
  void filingLeavesTheOrderItsLinesAndItsEventsExactlyAsTheyWere() throws SQLException {
    Shop s = shop("disc-unchanged");
    Order o = deliveredHam(s, s.rosa());
    var before = given().basePath("").get("/api/orders/" + o.id());
    String stored = orderRows(o);

    fileAsReseller(s.rosaToken(), o.id(), body(line(s.ham(), "8"))).then().statusCode(201);

    var after = given().basePath("").get("/api/orders/" + o.id());
    assertEquals(before.asString(), after.asString());
    assertEquals(stored, orderRows(o));
    after.then().body("status", is("delivered")).body("total", is(1200.0f));
  }

  private String orderRows(Order o) throws SQLException {
    try (var c = source.getConnection()) {
      return TestSql.rows(
                  c,
                  "SELECT status, paid, total, note, delivery_date, delivered_at, paid_at"
                      + " FROM sales_order WHERE id=?",
                  o.id())
              .toString()
          + TestSql.rows(
                  c,
                  "SELECT product_id, quantity, price, total FROM order_line WHERE order_id=?"
                      + " ORDER BY id",
                  o.id())
              + TestSql.rows(c, "SELECT * FROM order_event WHERE order_id=?", o.id());
    }
  }

  @Test
  @TestSecurity(
      user = "disc-replay",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "disc-replay")})
  void theSameKeyOnTheSameOrderByTheSameFilerReplaysTheReportWithoutASecondOne()
      throws SQLException {
    Shop s = shop("disc-replay");
    Order o = deliveredHam(s, s.rosa());
    UUID key = UUID.randomUUID();
    String body = body(key, "Short", line(s.ham(), "8"));

    var first = fileAsReseller(s.rosaToken(), o.id(), body).then().statusCode(201).extract();
    var again = fileAsReseller(s.rosaToken(), o.id(), body).then().statusCode(200).extract();

    assertEquals(first.jsonPath().getMap("$"), again.jsonPath().getMap("$"));
    assertEquals(1, reports(s, o));

    // The owner replays an owner-filed report the same way.
    Order mine;
    try (var c = source.getConnection()) {
      mine = delivered(c, s, s.rosa(), ham(s, "10"));
    }
    UUID ownerKey = UUID.randomUUID();
    String ownerBody = body(ownerKey, null, line(s.ham(), "9"));
    var filed = fileAsOwner(mine.id(), ownerBody).then().statusCode(201).extract();
    var replay = fileAsOwner(mine.id(), ownerBody).then().statusCode(200).extract();
    assertEquals(filed.jsonPath().getMap("$"), replay.jsonPath().getMap("$"));
    assertEquals(1, reports(s, mine));
  }

  @Test
  @TestSecurity(
      user = "disc-key-reuse",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "disc-key-reuse")})
  void aKeyUsedByAnotherFilerOrOnAnotherOrderIsRefusedAndRevealsNothing() throws SQLException {
    Shop s = shop("disc-key-reuse");
    Order rosas = deliveredHam(s, s.rosa());
    Order rosasSecond = deliveredHam(s, s.rosa());
    Order bens = deliveredHam(s, s.ben());
    UUID key = UUID.randomUUID();
    String secret = "Rosa-only-note-7731";
    fileAsReseller(s.rosaToken(), rosas.id(), body(key, secret, line(s.ham(), "8")))
        .then()
        .statusCode(201);
    String reuse = body(key, null, line(s.ham(), "9"));

    // Another reseller, on their own order.
    var byBen = fileAsReseller(s.benToken(), bens.id(), reuse);
    // The same reseller, on another order.
    var elsewhere = fileAsReseller(s.rosaToken(), rosasSecond.id(), reuse);
    // The owner, on the order the key already belongs to: another filer.
    var byOwner = fileAsOwner(rosas.id(), reuse);

    for (var response : List.of(byBen, elsewhere, byOwner)) {
      response
          .then()
          .statusCode(409)
          .contentType("application/problem+json")
          .body("detail", is("Idempotency key already used"));
      String text = response.asString();
      assertFalse(text.contains(secret), text);
      assertFalse(text.contains("shortfallTotal"), text);
      assertFalse(text.contains("orderNumber"), text);
    }
    assertEquals(0, reports(s, bens));
    assertEquals(0, reports(s, rosasSecond));
    assertEquals(1, reports(s, rosas));
  }

  @Test
  @TestSecurity(
      user = "disc-status",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "disc-status")})
  void onlyADeliveredOrderTakesAReport() throws SQLException {
    Shop s = shop("disc-status");
    for (String status : new String[] {"placed", "confirmed", "cancelled"}) {
      Order o;
      try (var c = source.getConnection()) {
        o = order(c, s, s.rosa(), status, ham(s, "10"));
      }
      fileAsReseller(s.rosaToken(), o.id(), body(line(s.ham(), "8")))
          .then()
          .statusCode(409)
          .body("detail", is("Order is now " + status));
      fileAsOwner(o.id(), body(line(s.ham(), "8")))
          .then()
          .statusCode(409)
          .body("detail", is("Order is now " + status));
      assertEquals(0, reports(s, o), status);
    }
  }

  @Test
  @TestSecurity(
      user = "disc-second",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "disc-second")})
  void anOrderTakesOneReportWhoeverTriesAgain() throws SQLException {
    Shop s = shop("disc-second");
    Order o = deliveredHam(s, s.rosa());
    fileAsReseller(s.rosaToken(), o.id(), body(UUID.randomUUID(), "First", line(s.ham(), "8")))
        .then()
        .statusCode(201);

    fileAsReseller(s.rosaToken(), o.id(), body(UUID.randomUUID(), "Again", line(s.ham(), "7")))
        .then()
        .statusCode(409)
        .body("detail", is("This order already has a report"));
    fileAsOwner(o.id(), body(UUID.randomUUID(), "Mine", line(s.ham(), "6")))
        .then()
        .statusCode(409)
        .body("detail", is("This order already has a report"));

    assertEquals(1, reports(s, o));
    readAsOwner(o.id()).then().body("note", is("First")).body("lines[0].receivedQuantity", is(8));
  }

  @Test
  @TestSecurity(
      user = "disc-lines",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "disc-lines")})
  void productsThatAreNotOnTheOrderOrAreRepeatedAreRefusedOnTheLine() throws SQLException {
    Shop s = shop("disc-lines");
    Shop other = shop("disc-lines-other");
    Order o;
    try (var c = source.getConnection()) {
      o = delivered(c, s, s.rosa(), ham(s, "10"), rice(s, "1.5"));
    }
    String notOnOrder = "This product is not on the order";

    // A product of the business that is not on the order, one that does not exist, and one of
    // another business get the same answer.
    for (long product : new long[] {s.saffron(), 999_999_999L, other.ham()})
      refused(
          fileAsReseller(s.rosaToken(), o.id(), body(line(s.ham(), "8"), line(product, "1"))),
          "lines[1].productId",
          notOnOrder);
    refused(
        fileAsOwner(o.id(), body(line(s.saffron(), "1"))), "lines[0].productId", notOnOrder);

    refused(
        fileAsReseller(s.rosaToken(), o.id(), body(line(s.ham(), "8"), line(s.ham(), "7"))),
        "lines[1].productId",
        "This product is already on another line");
    assertEquals(0, reports(s, o));
  }

  @Test
  @TestSecurity(
      user = "disc-quantities",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "disc-quantities")})
  void receivedQuantitiesOutsideTheRuleAreRefusedOnTheLine() throws SQLException {
    Shop s = shop("disc-quantities");
    Order o;
    try (var c = source.getConnection()) {
      o = delivered(c, s, s.rosa(), ham(s, "10"), rice(s, "1.5"));
    }
    // What was ordered, whatever the scale, is not a difference.
    for (String same : new String[] {"10", "10.0", "10.000"})
      refused(
          fileAsReseller(s.rosaToken(), o.id(), body(line(s.ham(), same))),
          "lines[0].receivedQuantity",
          "Received quantity matches what was ordered");
    refused(
        fileAsReseller(s.rosaToken(), o.id(), body(line(s.ham(), "8"), line(s.rice(), "1.50"))),
        "lines[1].receivedQuantity",
        "Received quantity matches what was ordered");
    // Out of range, too many decimals, not a number.
    for (String bad : new String[] {"-1", "-0.001", "9999.001", "10000", "1.2345", "0.0005", "\"abc\"", "null"})
      refused(
          fileAsReseller(s.rosaToken(), o.id(), body(line(s.rice(), bad))),
          "lines[0].receivedQuantity");
    refused(
        fileAsReseller(s.rosaToken(), o.id(), body(line(s.ham(), "8"), line(s.rice(), "1e400"))),
        "lines[1].receivedQuantity");
    // A fraction of a pack.
    refused(
        fileAsReseller(s.rosaToken(), o.id(), body(line(s.ham(), "9.5"))),
        "lines[0].receivedQuantity",
        "Hamonado is sold by the pack, so enter a whole number");
    assertEquals(0, reports(s, o));
  }

  @Test
  @TestSecurity(
      user = "disc-shape",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "disc-shape")})
  void aMalformedRequestIsRefusedByFieldAndNeverAServerError() throws SQLException {
    Shop s = shop("disc-shape");
    Order o = deliveredHam(s, s.rosa());
    String one = line(s.ham(), "8");

    refused(fileAsReseller(s.rosaToken(), o.id(), "{\"lines\":[],\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"), "lines");
    refused(fileAsReseller(s.rosaToken(), o.id(), body(repeated(one, 51))), "lines");
    refused(
        fileAsReseller(s.rosaToken(), o.id(), "{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"),
        "lines");
    refused(fileAsReseller(s.rosaToken(), o.id(), "{\"lines\":[" + one + "]}"), "idempotencyKey");
    refused(
        fileAsReseller(s.rosaToken(), o.id(), "{\"lines\":[" + one + "],\"idempotencyKey\":\"nope\"}"),
        "idempotencyKey");
    refused(fileAsReseller(s.rosaToken(), o.id(), body("{\"receivedQuantity\":8}")), "lines[0].productId");
    refused(fileAsReseller(s.rosaToken(), o.id(), body(line(0, "8"))), "lines[0].productId");
    refused(fileAsReseller(s.rosaToken(), o.id(), body("{\"productId\":" + s.ham() + "}")), "lines[0].receivedQuantity");
    refused(fileAsReseller(s.rosaToken(), o.id(), ""), "body");
    refused(
        fileAsReseller(s.rosaToken(), o.id(), body(UUID.randomUUID(), "x".repeat(501), one)),
        "note");
    refused(fileAsOwner(o.id(), body(UUID.randomUUID(), "x".repeat(501), one)), "note");
    assertEquals(0, reports(s, o));

    // 50 lines is the most, and 500 characters is the longest note.
    fileAsReseller(s.rosaToken(), o.id(), body(UUID.randomUUID(), "x".repeat(500), one))
        .then()
        .statusCode(201);
  }

  @Test
  @TestSecurity(
      user = "disc-ceiling",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "disc-ceiling")})
  void aShortfallThatTheMoneyColumnCannotHoldIsRefusedNotAnError() throws SQLException {
    Shop s = shop("disc-ceiling");
    Order o;
    try (var c = source.getConnection()) {
      o = delivered(c, s, s.rosa(), saffron(s), truffle(s));
    }

    // One line: 3 kg more than ordered at 4,000,000,000.00 is 12 billion.
    refused(
        fileAsReseller(s.rosaToken(), o.id(), body(line(s.saffron(), "4"))),
        "lines[0].receivedQuantity",
        "Shortfall is too large");
    // Each line fits, the sum does not.
    refused(
        fileAsReseller(
            s.rosaToken(), o.id(), body(line(s.saffron(), "3"), line(s.truffle(), "3"))),
        "lines",
        "Shortfall is too large");
    assertEquals(0, reports(s, o));

    fileAsReseller(s.rosaToken(), o.id(), body(line(s.saffron(), "3"))).then().statusCode(201);
  }

  @Test
  @TestSecurity(
      user = "disc-forged",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "disc-forged")})
  void valuesTheRequestSuppliesAreIgnoredAndStoredOnesComeFromTheOrderAndTheCredential()
      throws SQLException {
    Shop s = shop("disc-forged");
    Order byReseller = deliveredHam(s, s.rosa());
    Order byOwner = deliveredHam(s, s.rosa());
    String forgedLine =
        "{\"productId\":"
            + s.ham()
            + ",\"receivedQuantity\":8,\"orderedQuantity\":99,\"price\":1,"
            + "\"shortfallTotal\":0,\"productName\":\"Mallory\",\"unit\":\"kg\"}";
    String forged =
        "\"filedBy\":\"%s\",\"status\":\"approved\",\"shortfallTotal\":0,\"resolvedBy\":\"owner\","
            + "\"resolvedAt\":\"2001-01-01T00:00:00Z\",\"filedAt\":\"2001-01-01T00:00:00Z\","
            + "\"resolutionNote\":\"Already fine\",\"tenantId\":1,\"orderId\":1,\"id\":1,";

    fileAsReseller(
            s.rosaToken(),
            byReseller.id(),
            "{" + forged.formatted("owner") + "\"lines\":[" + forgedLine + "],\"idempotencyKey\":\""
                + UUID.randomUUID() + "\"}")
        .then()
        .statusCode(201)
        .body("filedBy", is("reseller"))
        .body("status", is("open"))
        .body("orderId", is((int) byReseller.id()))
        .body("resolvedBy", nullValue())
        .body("resolutionNote", is(""))
        .body("shortfallTotal", is(240.0f))
        .body("lines[0].orderedQuantity", is(10))
        .body("lines[0].price", is(120.0f))
        .body("lines[0].productName", is("Hamonado"))
        .body("lines[0].unit", is("pack"))
        .body("lines[0].shortfallTotal", is(240.0f));
    fileAsOwner(
            byOwner.id(),
            "{" + forged.formatted("reseller") + "\"lines\":[" + forgedLine + "],\"idempotencyKey\":\""
                + UUID.randomUUID() + "\"}")
        .then()
        .statusCode(201)
        .body("filedBy", is("owner"))
        .body("status", is("open"))
        .body("orderId", is((int) byOwner.id()));

    try (var c = source.getConnection()) {
      for (Order o : List.of(byReseller, byOwner)) {
        var row =
            TestSql.one(
                c,
                "SELECT tenant_id, status, resolved_by, resolved_at, shortfall_total, filed_at"
                    + " FROM discrepancy_report WHERE order_id=?",
                o.id());
        assertEquals(s.tenant(), ((Number) row.get("tenant_id")).longValue());
        assertEquals("open", row.get("status"));
        assertNull(row.get("resolved_by"));
        assertNull(row.get("resolved_at"));
        assertEquals(0, new java.math.BigDecimal("240.00").compareTo((java.math.BigDecimal) row.get("shortfall_total")));
        assertTrue(((java.sql.Timestamp) row.get("filed_at")).toInstant().isAfter(Instant.now().minusSeconds(60)));
      }
    }
  }
}
