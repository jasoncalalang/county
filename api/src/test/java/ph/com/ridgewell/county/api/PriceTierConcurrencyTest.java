package ph.com.ridgewell.county.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/**
 * A product's tier prices are guarded by the product lock: an owner editing a tier price holds the
 * product's exclusive lock, so a concurrent placement waits and then captures the committed tier
 * price. This keeps the reseller -> business -> product -> order lock order (ADR 0013).
 */
@QuarkusTest
class PriceTierConcurrencyTest {
  @Inject AgroalDataSource source;

  record Fixture(long tenant, long product, long tier, String token) {}

  private Fixture fixture() throws SQLException {
    String token = UUID.randomUUID().toString().replace("-", "").substring(0, 22);
    try (Connection c = source.getConnection()) {
      long tenant = TestOwners.createBusiness(c, UUID.randomUUID().toString());
      long product =
          ConcurrentOrderingTest.insert(
              c,
              "INSERT INTO product(tenant_id,name,unit,price) VALUES (?,'Original','pack',120)"
                  + " RETURNING id",
              tenant);
      long tier =
          ConcurrentOrderingTest.insert(
              c, "INSERT INTO price_tier(tenant_id,name) VALUES (?,'Wholesale') RETURNING id", tenant);
      long reseller =
          ConcurrentOrderingTest.insert(
              c,
              "INSERT INTO reseller(tenant_id,name,mobile,token) VALUES (?,'Buyer','+639171234567',?)"
                  + " RETURNING id",
              tenant,
              token);
      ConcurrentOrderingTest.insert(
          c,
          "INSERT INTO product_tier_price(tenant_id,product_id,price_tier_id,price) VALUES"
              + " (?,?,?,100) RETURNING tenant_id",
          tenant,
          product,
          tier);
      try (var s = c.prepareStatement("UPDATE reseller SET price_tier_id=? WHERE id=?")) {
        s.setLong(1, tier);
        s.setLong(2, reseller);
        s.executeUpdate();
      }
      return new Fixture(tenant, product, tier, token);
    }
  }

  @Test
  void placementWaitsForTierPriceEditAndCapturesTheCommittedTierPrice() throws Exception {
    var f = fixture();
    try (var pool = Executors.newSingleThreadExecutor();
        Connection c = source.getConnection()) {
      c.setAutoCommit(false);
      // Hold the product's exclusive lock, as the owner's set-tier-price does, then change the price.
      try (var lock = c.prepareStatement("SELECT id FROM product WHERE id=? FOR UPDATE")) {
        lock.setLong(1, f.product());
        lock.executeQuery().close();
      }
      try (var update =
          c.prepareStatement(
              "UPDATE product_tier_price SET price=150 WHERE tenant_id=? AND product_id=? AND"
                  + " price_tier_id=?")) {
        update.setLong(1, f.tenant());
        update.setLong(2, f.product());
        update.setLong(3, f.tier());
        update.executeUpdate();
      }
      var result =
          pool.submit(
              () ->
                  given()
                      .basePath("")
                      .contentType("application/json")
                      .body(ConcurrentOrderingTest.body(f.product(), 2, UUID.randomUUID()))
                      .post("/api/order/" + f.token() + "/orders"));
      awaitProductWait(f);
      assertFalse(result.isDone(), "placement must wait for the in-flight tier-price edit");
      c.commit();
      var response = result.get(30, TimeUnit.SECONDS);
      assertEquals(201, response.statusCode(), response.asString());
      assertEquals(
          0, new BigDecimal("150.00").compareTo(new BigDecimal(response.jsonPath().getString("lines[0].price"))));
      assertEquals(
          0, new BigDecimal("300.00").compareTo(new BigDecimal(response.jsonPath().getString("total"))));
    }
  }

  private void awaitProductWait(Fixture f) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    try (Connection c = source.getConnection();
        var s =
            c.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND"
                    + " wait_event_type='Lock' AND query ILIKE '%from product %for share%'")) {
      while (System.nanoTime() < deadline) {
        try (var r = s.executeQuery()) {
          r.next();
          if (r.getInt(1) > 0) return;
        }
        Thread.sleep(25);
      }
    }
    fail("Placement for product " + f.product() + " never reached the product lock");
  }
}
