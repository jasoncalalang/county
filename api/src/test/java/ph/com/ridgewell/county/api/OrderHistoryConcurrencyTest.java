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
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** A6: the order's row lock serialises actions, so a race records one event, not two. */
@QuarkusTest
class OrderHistoryConcurrencyTest {
  @Inject AgroalDataSource source;

  private static Callable<Response> confirm(long order) {
    return () -> given().basePath("").post("/api/orders/" + order + "/confirm");
  }

  @Test
  @TestSecurity(
      user = "history-race",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "history-race")})
  void twoConfirmsOfOneOrderGiveOneSuccessOneConflictAndOneEvent() throws Exception {
    long tenant, order;
    try (Connection c = source.getConnection()) {
      tenant = TestOwners.createBusiness(c, "history-race");
      long reseller =
          TestSql.id(
              TestSql.one(
                  c,
                  "INSERT INTO reseller(tenant_id,name,mobile,token) VALUES (?,'Rosa',"
                      + "'+639171234567',?) RETURNING id",
                  tenant,
                  UUID.randomUUID().toString().replace("-", "").substring(0, 22)));
      order =
          TestSql.id(
              TestSql.one(
                  c,
                  "INSERT INTO sales_order(tenant_id,reseller_id,number,placed_at,delivery_date,"
                      + "total,idempotency_key) VALUES (?,?,1,now(),current_date,120,?)"
                      + " RETURNING id",
                  tenant,
                  reseller,
                  UUID.randomUUID()));
    }
    List<Response> responses;
    try (var pool = Executors.newFixedThreadPool(2);
        Connection holder = source.getConnection()) {
      // Hold the order's row lock so both confirms are in flight, waiting on it, together.
      holder.setAutoCommit(false);
      TestSql.rows(holder, "SELECT id FROM sales_order WHERE id=? FOR UPDATE", order);
      var calls = List.of(pool.submit(confirm(order)), pool.submit(confirm(order)));
      awaitWaiting(2);
      holder.commit();
      responses =
          calls.stream()
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

    assertEquals(1, responses.stream().filter(r -> r.statusCode() == 200).count());
    assertEquals(1, responses.stream().filter(r -> r.statusCode() == 409).count());
    try (Connection c = source.getConnection()) {
      assertEquals(
          1L,
          TestSql.one(
                  c,
                  "SELECT count(*) AS n FROM order_event WHERE tenant_id=? AND order_id=?",
                  tenant,
                  order)
              .get("n"));
    }
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
