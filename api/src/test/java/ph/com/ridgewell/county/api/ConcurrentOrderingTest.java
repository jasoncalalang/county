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
class ConcurrentOrderingTest {
  @Inject AgroalDataSource source;

  @Test
  void simultaneousReplayCreatesOneOrderAndNoNumberGap() throws Exception {
    String token = UUID.randomUUID().toString().replace("-", "").substring(0, 22);
    long tenant, product;
    try (Connection c = source.getConnection()) {
      tenant = TestOwners.createBusiness(c, UUID.randomUUID().toString());
      product =
          insert(
              c,
              "INSERT INTO product(tenant_id,name,unit,price) VALUES (?,'Product','pack',120)"
                  + " RETURNING id",
              tenant);
      insert(
          c,
          "INSERT INTO reseller(tenant_id,name,mobile,token) VALUES (?,'Rosa','+639171234567',?)"
              + " RETURNING id",
          tenant,
          token);
    }
    String body = body(product, 1, UUID.randomUUID());
    var ready = new CountDownLatch(4);
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(4)) {
      var results = new ArrayList<Future<io.restassured.response.Response>>();
      for (int i = 0; i < 4; i++)
        results.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return given()
                      .basePath("")
                      .contentType("application/json")
                      .body(body)
                      .post("/api/order/" + token + "/orders");
                }));
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      Set<Long> ids = new HashSet<>();
      int created = 0;
      for (var future : results) {
        var response = future.get(30, TimeUnit.SECONDS);
        assertTrue(
            response.statusCode() == 200 || response.statusCode() == 201, response.asString());
        if (response.statusCode() == 201) created++;
        ids.add(response.jsonPath().getLong("id"));
      }
      assertEquals(1, created);
      assertEquals(1, ids.size());
    }
    // Failed order must not consume a per-business order number.
    given()
        .basePath("")
        .contentType("application/json")
        .body(body(product, 10000, UUID.randomUUID()))
        .post("/api/order/" + token + "/orders")
        .then()
        .statusCode(422);
    var second =
        given()
            .basePath("")
            .contentType("application/json")
            .body(body(product, 1, UUID.randomUUID()))
            .post("/api/order/" + token + "/orders");
    assertEquals(201, second.statusCode());
    assertEquals(2, second.jsonPath().getInt("number"));
    try (Connection c = source.getConnection();
        var s =
            c.prepareStatement(
                "SELECT count(*),max(number) FROM purchase_order WHERE tenant_id=?")) {
      s.setLong(1, tenant);
      try (var rs = s.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(2, rs.getLong(1));
        assertEquals(2, rs.getLong(2));
      }
    }
  }

  static String body(long product, int quantity, UUID key) {
    return "{\"lines\":[{\"productId\":"
        + product
        + ",\"quantity\":"
        + quantity
        + "}],\"idempotencyKey\":\""
        + key
        + "\"}";
  }

  static long insert(Connection c, String sql, Object... args) throws SQLException {
    try (var s = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
      try (var r = s.executeQuery()) {
        r.next();
        return r.getLong(1);
      }
    }
  }
}
