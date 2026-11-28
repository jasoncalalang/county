package ph.com.ridgewell.county.api;

import static org.junit.jupiter.api.Assertions.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR 0017 in the schema: quantity is numeric(7,3) above 0 and at most 9999, on both tables. */
@QuarkusTest
class FractionalQuantitySchemaTest {
  @Inject AgroalDataSource source;

  record Rows(long tenant, long product, long order, long quote) {}

  private Rows fixture(Connection c) throws SQLException {
    long tenant = TestOwners.createBusiness(c, "fraction-schema-" + UUID.randomUUID());
    long product =
        TestSql.id(
            TestSql.one(
                c,
                "INSERT INTO product(tenant_id,name,unit,price) VALUES (?,'Rice','kg',120)"
                    + " RETURNING id",
                tenant));
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
    long quote =
        TestSql.id(
            TestSql.one(
                c,
                "INSERT INTO quote(tenant_id,reseller_id,number,created_at,expires_on,"
                    + "delivery_date,total) VALUES (?,?,1,now(),current_date,current_date,120)"
                    + " RETURNING id",
                tenant,
                reseller));
    return new Rows(tenant, product, order, quote);
  }

  @Test
  void quantityIsNumericSevenThreeOnBothLineTables() throws SQLException {
    try (Connection c = source.getConnection()) {
      for (String table : new String[] {"order_line", "quote_line"}) {
        var column =
            TestSql.one(
                c,
                "SELECT data_type, numeric_precision, numeric_scale FROM information_schema.columns"
                    + " WHERE table_schema = current_schema() AND table_name = ?"
                    + " AND column_name = 'quantity'",
                table);
        assertEquals("numeric", column.get("data_type"), table);
        assertEquals(7, column.get("numeric_precision"), table);
        assertEquals(3, column.get("numeric_scale"), table);
      }
    }
  }

  @Test
  void theDatabaseRefusesAQuantityOutsideTheRule() throws SQLException {
    try (Connection c = source.getConnection()) {
      Rows r = fixture(c);
      String order =
          "INSERT INTO order_line(tenant_id,order_id,product_id,product_name,unit,quantity,price,"
              + "total) VALUES (?,?,?,'Rice','kg',?::numeric,120,120)";
      String quote =
          "INSERT INTO quote_line(tenant_id,quote_id,product_id,product_name,unit,quantity,price,"
              + "total) VALUES (?,?,?,'Rice','kg',?::numeric,120,120)";
      for (String quantity : new String[] {"0", "-1", "0.0004", "9999.001", "10000", "9999.9995"}) {
        assertThrows(
            SQLException.class,
            () -> TestSql.execute(c, order, r.tenant, r.order, r.product, quantity),
            "order_line " + quantity);
        assertThrows(
            SQLException.class,
            () -> TestSql.execute(c, quote, r.tenant, r.quote, r.product, quantity),
            "quote_line " + quantity);
      }
      assertEquals(
          0, TestSql.rows(c, "SELECT id FROM order_line WHERE order_id=?", r.order).size());
      for (String quantity : new String[] {"0.001", "9999"}) {
        TestSql.execute(c, "DELETE FROM order_line WHERE order_id=?", r.order);
        TestSql.execute(c, "DELETE FROM quote_line WHERE quote_id=?", r.quote);
        assertEquals(1, TestSql.execute(c, order, r.tenant, r.order, r.product, quantity));
        assertEquals(1, TestSql.execute(c, quote, r.tenant, r.quote, r.product, quantity));
      }
    }
  }
}
