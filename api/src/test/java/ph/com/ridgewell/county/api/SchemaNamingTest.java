package ph.com.ridgewell.county.api;

import static org.junit.jupiter.api.Assertions.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR 0016: the reseller's order table is sales_order, and nothing is left named purchase_order. */
@QuarkusTest
class SchemaNamingTest {
  @Inject AgroalDataSource source;

  @Test
  void ordersLiveInSalesOrder() throws Exception {
    try (Connection c = source.getConnection()) {
      var row =
          TestSql.one(
              c,
              "SELECT to_regclass(current_schema() || '.sales_order')::text AS present,"
                  + " to_regclass(current_schema() || '.purchase_order')::text AS absent");
      assertNotNull(row.get("present"));
      assertNull(row.get("absent"));
    }
  }

  @Test
  void noRelationOrConstraintKeepsTheOldName() throws Exception {
    try (Connection c = source.getConnection()) {
      List<?> leftovers =
          TestSql.rows(
              c,
              "SELECT relname AS name FROM pg_class"
                  + " WHERE relnamespace = current_schema()::regnamespace"
                  + " AND relname LIKE 'purchase_order%'"
                  + " UNION ALL SELECT conname FROM pg_constraint"
                  + " WHERE connamespace = current_schema()::regnamespace"
                  + " AND conname LIKE 'purchase_order%'");
      assertEquals(List.of(), leftovers);
    }
  }
}
