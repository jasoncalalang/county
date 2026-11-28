package ph.com.ridgewell.county.api;

import static org.junit.jupiter.api.Assertions.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR 0018 in the schema: every reseller link has an expiry, 90 days out unless set. */
@QuarkusTest
class ResellerLinkSchemaTest {
  @Inject AgroalDataSource source;

  @Test
  void theExpiryColumnIsRequired() throws SQLException {
    try (Connection c = source.getConnection()) {
      var column =
          TestSql.rows(
              c,
              "SELECT is_nullable, data_type FROM information_schema.columns"
                  + " WHERE table_schema = current_schema() AND table_name = 'reseller'"
                  + " AND column_name = 'token_expires_at'");

      assertEquals(1, column.size(), "reseller.token_expires_at is missing");
      assertEquals("NO", column.getFirst().get("is_nullable"));
      assertEquals("timestamp with time zone", column.getFirst().get("data_type"));
    }
  }

  @Test
  void aResellerInsertedWithoutAnExpiryGetsNinetyDays() throws SQLException {
    try (Connection c = source.getConnection()) {
      long tenant = TestOwners.createBusiness(c, "link-schema-" + UUID.randomUUID());
      long reseller =
          TestSql.id(
              TestSql.one(
                  c,
                  "INSERT INTO reseller(tenant_id,name,mobile,token) VALUES (?,'Rosa',"
                      + "'+639171234567',?) RETURNING id",
                  tenant,
                  UUID.randomUUID().toString().replace("-", "").substring(0, 22)));

      var row =
          TestSql.one(
              c,
              "SELECT token_expires_at AS expires, now() AS db_now FROM reseller WHERE id=?",
              reseller);
      Duration ahead =
          Duration.between(
              ((Timestamp) row.get("db_now")).toInstant(),
              ((Timestamp) row.get("expires")).toInstant());

      assertTrue(
          ahead.minus(Duration.ofDays(90)).abs().compareTo(Duration.ofMinutes(1)) < 0,
          "expected about 90 days, got " + ahead);
    }
  }
}
