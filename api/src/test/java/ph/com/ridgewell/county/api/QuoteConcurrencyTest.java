package ph.com.ridgewell.county.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/**
 * Quote locks (ADR 0015): accepting takes the reseller, the business, then the quote; creating
 * share-locks the products it prices. These tests wait on the real row locks.
 */
@QuarkusTest
class QuoteConcurrencyTest {
  @Inject AgroalDataSource source;

  private static Callable<Response> accept(String token, long quote) {
    String body = "{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}";
    return () ->
        given()
            .basePath("")
            .contentType("application/json")
            .body(body)
            .post("/api/order/" + token + "/quotes/" + quote + "/accept");
  }

  @Test
  void concurrentAcceptsWithDistinctKeysPlaceOneOrder() throws Exception {
    QuoteIsolationTest.Tenant t;
    long quote;
    try (Connection c = source.getConnection()) {
      t = QuoteIsolationTest.tenant(c, "quote-race-" + UUID.randomUUID());
      quote = QuoteIsolationTest.sentQuote(c, t, 1);
    }
    List<Response> responses;
    try (var pool = Executors.newFixedThreadPool(4)) {
      List<Callable<Response>> calls = List.of(
          accept(t.token(), quote), accept(t.token(), quote),
          accept(t.token(), quote), accept(t.token(), quote));
      responses = pool.invokeAll(calls).stream().map(f -> {
        try {
          return f.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      }).toList();
    }
    assertEquals(1, responses.stream().filter(r -> r.statusCode() == 201).count());
    assertEquals(3, responses.stream().filter(r -> r.statusCode() == 200).count());
    assertEquals(1, responses.stream().map(r -> r.jsonPath().getLong("id")).distinct().count());
    try (Connection c = source.getConnection()) {
      var row =
          TestSql.one(
              c,
              "SELECT count(*) AS orders, max(number) AS number FROM sales_order WHERE"
                  + " tenant_id=?",
              t.id());
      assertEquals(1L, ((Number) row.get("orders")).longValue());
      assertEquals(1L, ((Number) row.get("number")).longValue());
      var business = TestSql.one(c, "SELECT next_order_number FROM business WHERE id=?", t.id());
      assertEquals(2L, ((Number) business.get("next_order_number")).longValue());
    }
  }

  @Test
  void acceptWaitsForAnInFlightCancelAndThenRefuses() throws Exception {
    QuoteIsolationTest.Tenant t;
    long quote;
    try (Connection c = source.getConnection()) {
      t = QuoteIsolationTest.tenant(c, "quote-cancel-race-" + UUID.randomUUID());
      quote = QuoteIsolationTest.sentQuote(c, t, 1);
    }
    try (var pool = Executors.newSingleThreadExecutor();
        Connection c = source.getConnection()) {
      c.setAutoCommit(false);
      // The owner's cancel holds the quote's write lock, uncommitted.
      TestSql.execute(
          c,
          "UPDATE quote SET status='cancelled', cancelled_at=now() WHERE id=?",
          quote);
      var result = pool.submit(accept(t.token(), quote));
      awaitWait("%from quote %for %update%");
      assertFalse(result.isDone(), "accept must wait for the in-flight cancel");
      c.commit();
      var response = result.get(30, TimeUnit.SECONDS);
      assertEquals(409, response.statusCode(), response.asString());
    }
    try (Connection c = source.getConnection()) {
      assertEquals(
          0L,
          ((Number)
                  TestSql.one(c, "SELECT count(*) AS n FROM sales_order WHERE tenant_id=?", t.id())
                      .get("n"))
              .longValue());
    }
  }

  @Test
  @TestSecurity(user = "quote-price-race", roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "quote-price-race")})
  void createWaitsForAnInFlightPriceEditAndCapturesTheCommittedPrice() throws Exception {
    QuoteIsolationTest.Tenant t;
    try (Connection c = source.getConnection()) {
      t = QuoteIsolationTest.tenant(c, "quote-price-race");
    }
    try (var pool = Executors.newSingleThreadExecutor();
        Connection c = source.getConnection()) {
      c.setAutoCommit(false);
      // The owner's product edit holds the product's write lock, uncommitted.
      TestSql.execute(c, "UPDATE product SET price=150 WHERE id=?", t.product());
      String body =
          "{\"resellerId\":"
              + t.reseller()
              + ",\"lines\":[{\"productId\":"
              + t.product()
              + ",\"quantity\":2}],\"expiresOn\":\""
              + LocalDate.now().plusDays(3)
              + "\"}";
      var result =
          pool.submit(
              () ->
                  given()
                      .basePath("")
                      .contentType("application/json")
                      .body(body)
                      .post("/api/quotes"));
      awaitWait("%from product %for share%");
      assertFalse(result.isDone(), "create must wait for the in-flight price edit");
      c.commit();
      var response = result.get(30, TimeUnit.SECONDS);
      assertEquals(201, response.statusCode(), response.asString());
      assertEquals(
          0,
          new BigDecimal("300.00").compareTo(new BigDecimal(response.jsonPath().getString("total"))));
    }
  }

  private void awaitWait(String query) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    try (Connection c = source.getConnection();
        var s =
            c.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND"
                    + " wait_event_type='Lock' AND query ILIKE ?")) {
      s.setString(1, query);
      while (System.nanoTime() < deadline) {
        try (var r = s.executeQuery()) {
          r.next();
          if (r.getInt(1) > 0) return;
        }
        Thread.sleep(25);
      }
    }
    fail("Never waited on a lock matching " + query);
  }
}
