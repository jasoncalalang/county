package ph.com.ridgewell.county.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ProductOrderingConcurrencyTest {
  @Inject AgroalDataSource source;

  record Fixture(long tenant, long product, String token) {}

  Fixture fixture() throws SQLException {
    String token = UUID.randomUUID().toString().replace("-", "").substring(0, 22);
    try (Connection c = source.getConnection()) {
      long tenant = TestOwners.createBusiness(c, UUID.randomUUID().toString());
      long product =
          ConcurrentOrderingTest.insert(
              c,
              "INSERT INTO product(tenant_id,name,unit,price) VALUES (?,'Original','pack',120)"
                  + " RETURNING id",
              tenant);
      ConcurrentOrderingTest.insert(
          c,
          "INSERT INTO reseller(tenant_id,name,mobile,token) VALUES (?,'Buyer','+639171234567',?)"
              + " RETURNING id",
          tenant,
          token);
      return new Fixture(tenant, product, token);
    }
  }

  io.restassured.response.Response place(Fixture f) {
    return given()
        .basePath("")
        .contentType("application/json")
        .body(ConcurrentOrderingTest.body(f.product(), 2, UUID.randomUUID()))
        .post("/api/order/" + f.token() + "/orders");
  }

  @Test
  void simultaneousDistinctKeysReceiveUniqueSequentialNumbers() throws Exception {
    var f = fixture();
    var ready = new CountDownLatch(4);
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(4)) {
      var results = new ArrayList<Future<io.restassured.response.Response>>();
      var buyers = new ArrayList<Fixture>();
      buyers.add(f);
      try (Connection c = source.getConnection()) {
        for (int i = 0; i < 3; i++) {
          String token = UUID.randomUUID().toString().replace("-", "").substring(0, 22);
          ConcurrentOrderingTest.insert(
              c,
              "INSERT INTO reseller(tenant_id,name,mobile,token) VALUES (?,'Other buyer',?,?)"
                  + " RETURNING id",
              f.tenant(),
              "+63917123456" + i,
              token);
          buyers.add(new Fixture(f.tenant(), f.product(), token));
        }
      }
      for (var buyer : buyers)
        results.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return place(buyer);
                }));
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      Set<Integer> numbers = new HashSet<>();
      Set<Long> ids = new HashSet<>();
      for (var result : results) {
        var response = result.get(30, TimeUnit.SECONDS);
        assertEquals(201, response.statusCode(), response.asString());
        numbers.add(response.jsonPath().getInt("number"));
        ids.add(response.jsonPath().getLong("id"));
      }
      assertEquals(Set.of(1, 2, 3, 4), numbers);
      assertEquals(4, ids.size());
    }
  }

  @Test
  void placementWaitsForProductEditAndSnapshotsCommittedValues() throws Exception {
    var f = fixture();
    try (var pool = Executors.newSingleThreadExecutor();
        Connection c = source.getConnection()) {
      c.setAutoCommit(false);
      try (var update =
          c.prepareStatement("UPDATE product SET name='Updated',price=150 WHERE id=?")) {
        update.setLong(1, f.product());
        update.executeUpdate();
      }
      var result = pool.submit(() -> place(f));
      awaitProductWait(f);
      assertFalse(result.isDone(), "placement must wait for the in-flight product edit");
      c.commit();
      var response = result.get(30, TimeUnit.SECONDS);
      assertEquals(201, response.statusCode(), response.asString());
      assertEquals("Updated", response.jsonPath().getString("lines[0].productName"));
      assertEquals(
          0,
          new java.math.BigDecimal("150.00")
              .compareTo(
                  new java.math.BigDecimal(response.jsonPath().getString("lines[0].price"))));
      assertEquals(
          0,
          new java.math.BigDecimal("300.00")
              .compareTo(new java.math.BigDecimal(response.jsonPath().getString("total"))));
    }
  }

  @Test
  void placementSeesConcurrentDeactivationAndDoesNotConsumeNumber() throws Exception {
    var f = fixture();
    try (var pool = Executors.newSingleThreadExecutor();
        Connection c = source.getConnection()) {
      c.setAutoCommit(false);
      try (var update = c.prepareStatement("UPDATE product SET active=false WHERE id=?")) {
        update.setLong(1, f.product());
        update.executeUpdate();
      }
      var result = pool.submit(() -> place(f));
      awaitProductWait(f);
      c.commit();
      var rejected = result.get(30, TimeUnit.SECONDS);
      assertEquals(422, rejected.statusCode(), rejected.asString());
      try (var update = c.prepareStatement("UPDATE product SET active=true WHERE id=?")) {
        update.setLong(1, f.product());
        update.executeUpdate();
      }
      c.commit();
      var accepted = place(f);
      assertEquals(201, accepted.statusCode(), accepted.asString());
      assertEquals(1, accepted.jsonPath().getInt("number"));
    }
  }

  // Inspect the database's actual lock wait rather than timing a sleep and assuming a race
  // occurred.
  void awaitProductWait(Fixture f) throws Exception {
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
    fail("Order for product " + f.product() + " never reached the product lock");
  }
}
