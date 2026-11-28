package ph.com.ridgewell.county.api;

import static org.junit.jupiter.api.Assertions.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A7: history is append-only. The database refuses to change or remove an event. */
@QuarkusTest
class OrderHistorySchemaTest {
  @Inject AgroalDataSource source;

  private long eventFixture(Connection c) throws SQLException {
    long tenant = TestOwners.createBusiness(c, "history-schema-" + UUID.randomUUID());
    long user =
        TestSql.id(TestSql.one(c, "SELECT owner_user_id AS id FROM business WHERE id=?", tenant));
    long reseller =
        TestSql.id(
            TestSql.one(
                c,
                "INSERT INTO reseller(tenant_id,name,mobile,token) VALUES (?,'Rosa',"
                    + "'+639171234567',?) RETURNING id",
                tenant,
                UUID.randomUUID().toString().replace("-", "").substring(0, 22)));
    long order =
        TestSql.id(
            TestSql.one(
                c,
                "INSERT INTO sales_order(tenant_id,reseller_id,number,placed_at,delivery_date,"
                    + "total,idempotency_key) VALUES (?,?,1,now(),current_date,120,?) RETURNING id",
                tenant,
                reseller,
                UUID.randomUUID()));
    return TestSql.id(
        TestSql.one(
            c,
            "INSERT INTO order_event(tenant_id,order_id,at,from_status,to_status,paid,"
                + "actor_type,actor_user_id) VALUES (?,?,now(),'placed','confirmed',false,'owner',?)"
                + " RETURNING id",
            tenant,
            order,
            user));
  }

  @Test
  void anEventCannotBeUpdated() throws SQLException {
    try (Connection c = source.getConnection()) {
      long event = eventFixture(c);

      assertThrows(
          SQLException.class,
          () -> TestSql.execute(c, "UPDATE order_event SET to_status='cancelled' WHERE id=?", event));
      assertThrows(
          SQLException.class,
          () -> TestSql.execute(c, "UPDATE order_event SET paid=true WHERE id=?", event));

      var row = TestSql.one(c, "SELECT to_status, paid FROM order_event WHERE id=?", event);
      assertEquals("confirmed", row.get("to_status"));
      assertEquals(false, row.get("paid"));
    }
  }

  @Test
  void anEventCannotBeDeleted() throws SQLException {
    try (Connection c = source.getConnection()) {
      long event = eventFixture(c);

      assertThrows(
          SQLException.class, () -> TestSql.execute(c, "DELETE FROM order_event WHERE id=?", event));

      assertEquals(1, TestSql.rows(c, "SELECT id FROM order_event WHERE id=?", event).size());
    }
  }
}
