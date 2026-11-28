package ph.com.ridgewell.county.api;

import static org.junit.jupiter.api.Assertions.*;
import static ph.com.ridgewell.county.api.DiscrepancyFixtures.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/**
 * The order's row lock is the one serialisation point for a report: two filings that race for one
 * order leave one report, and a repeated key races to a replay, not a second report.
 */
@QuarkusTest
class DiscrepancyConcurrencyTest {
  @Inject AgroalDataSource source;

  record Race(Shop shop, Order order) {}

  private Race race(String subject) throws Exception {
    try (Connection c = source.getConnection()) {
      Shop s = DiscrepancyFixtures.shop(c, subject);
      return new Race(s, delivered(c, s, s.rosa(), ham(s, "10")));
    }
  }

  /** Runs both calls while a session holds the order's row lock, so both are waiting on it. */
  private List<Response> together(long order, Callable<Response> first, Callable<Response> second)
      throws Exception {
    try (var pool = Executors.newFixedThreadPool(2);
        Connection holder = source.getConnection()) {
      holder.setAutoCommit(false);
      TestSql.rows(holder, "SELECT id FROM sales_order WHERE id=? FOR UPDATE", order);
      var calls = List.of(pool.submit(first), pool.submit(second));
      awaitWaiting(2);
      holder.commit();
      return calls.stream()
          .map(
              f -> {
                try {
                  return f.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              })
          .toList();
    }
  }

  private long reports(Race r) throws Exception {
    try (Connection c = source.getConnection()) {
      return DiscrepancyFixtures.reports(c, r.shop().tenant(), r.order().id());
    }
  }

  @Test
  @TestSecurity(
      user = "disc-race-owners",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "disc-race-owners")})
  void twoFilingsOfOneOrderWithDifferentKeysGiveOneReportAndOneConflict() throws Exception {
    Race r = race("disc-race-owners");
    String one = line(r.shop().ham(), "8");

    var responses =
        together(
            r.order().id(),
            () -> fileAsOwner(r.order().id(), body(one)),
            () -> fileAsOwner(r.order().id(), body(one)));

    assertEquals(1, responses.stream().filter(x -> x.statusCode() == 201).count());
    assertEquals(1, responses.stream().filter(x -> x.statusCode() == 409).count());
    assertEquals(1, reports(r));
  }

  @Test
  @TestSecurity(
      user = "disc-race-parties",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "disc-race-parties")})
  void aResellerAndTheOwnerFilingTogetherGiveOneReportAndOneConflict() throws Exception {
    Race r = race("disc-race-parties");
    String one = line(r.shop().ham(), "8");

    var responses =
        together(
            r.order().id(),
            () -> fileAsReseller(r.shop().rosaToken(), r.order().id(), body(one)),
            () -> fileAsOwner(r.order().id(), body(one)));

    assertEquals(1, responses.stream().filter(x -> x.statusCode() == 201).count());
    assertEquals(1, responses.stream().filter(x -> x.statusCode() == 409).count());
    assertEquals(1, reports(r));
  }

  @Test
  @TestSecurity(
      user = "disc-race-replay",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "disc-race-replay")})
  void theSameKeyFiledTwiceTogetherGivesOneReportAndOneReplay() throws Exception {
    Race r = race("disc-race-replay");
    String same = body(UUID.randomUUID(), "Once", line(r.shop().ham(), "8"));

    var responses =
        together(
            r.order().id(),
            () -> fileAsOwner(r.order().id(), same),
            () -> fileAsOwner(r.order().id(), same));

    assertEquals(1, responses.stream().filter(x -> x.statusCode() == 201).count());
    assertEquals(1, responses.stream().filter(x -> x.statusCode() == 200).count());
    assertEquals(
        responses.get(0).jsonPath().getMap("$"), responses.get(1).jsonPath().getMap("$"));
    assertEquals(1, reports(r));
  }

  @Test
  @TestSecurity(
      user = "disc-race-resolve",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "disc-race-resolve")})
  void approvingAndDecliningTogetherResolvesOnceAndConflictsOnce() throws Exception {
    Race r = race("disc-race-resolve");
    fileAsReseller(r.shop().rosaToken(), r.order().id(), body(line(r.shop().ham(), "8")))
        .then()
        .statusCode(201);

    var responses =
        together(
            r.order().id(),
            () -> resolveAsOwner(r.order().id(), "approve", "{}"),
            () -> resolveAsOwner(r.order().id(), "decline", "{}"));

    assertEquals(1, responses.stream().filter(x -> x.statusCode() == 200).count());
    assertEquals(1, responses.stream().filter(x -> x.statusCode() == 409).count());
  }

  /** Waits until this many sessions are blocked on a lock of the order row. */
  private void awaitWaiting(int sessions) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    try (Connection c = source.getConnection();
        var s =
            c.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND"
                    + " wait_event_type='Lock' AND query ILIKE '%from sales_order%for%update%'")) {
      while (System.nanoTime() < deadline) {
        try (var r = s.executeQuery()) {
          r.next();
          if (r.getInt(1) >= sessions) return;
        }
        Thread.sleep(25);
      }
    }
    fail("Fewer than " + sessions + " sessions waited on the order lock");
  }
}
