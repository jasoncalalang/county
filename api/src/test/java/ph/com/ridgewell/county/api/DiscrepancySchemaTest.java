package ph.com.ridgewell.county.api;

import static org.junit.jupiter.api.Assertions.*;
import static ph.com.ridgewell.county.api.DiscrepancyFixtures.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * C10: a report changes only by being resolved, once, and nothing removes it or its lines. The
 * database refuses, whatever the application does.
 */
@QuarkusTest
class DiscrepancySchemaTest {
  @Inject AgroalDataSource source;

  record Fixture(Shop shop, Order order, long report) {}

  private Fixture fixture(Connection c, String filedBy) throws SQLException {
    Shop s = DiscrepancyFixtures.shop(c, "disc-schema-" + UUID.randomUUID());
    Order o = delivered(c, s, s.rosa(), ham(s, "10"));
    return new Fixture(s, o, report(c, s, o, filedBy));
  }

  private static Object value(Connection c, String sql, long id) throws SQLException {
    return TestSql.one(c, sql, id).get("v");
  }

  @Test
  void aLineCannotBeUpdatedOrDeleted() throws SQLException {
    try (Connection c = source.getConnection()) {
      var f = fixture(c, "reseller");
      long line =
          TestSql.id(TestSql.one(c, "SELECT id FROM discrepancy_line WHERE report_id=?", f.report()));

      assertThrows(
          SQLException.class,
          () -> TestSql.execute(c, "UPDATE discrepancy_line SET received_quantity=9 WHERE id=?", line));
      assertThrows(
          SQLException.class,
          () -> TestSql.execute(c, "UPDATE discrepancy_line SET shortfall_total=0 WHERE id=?", line));
      assertThrows(
          SQLException.class,
          () -> TestSql.execute(c, "DELETE FROM discrepancy_line WHERE id=?", line));

      assertEquals(
          0,
          ((java.math.BigDecimal)
                  value(c, "SELECT received_quantity - 8 AS v FROM discrepancy_line WHERE id=?", line))
              .signum());
    }
  }

  @Test
  void aReportCannotBeDeletedAndAnUnresolvedOneChangesOnlyByBeingResolved() throws SQLException {
    try (Connection c = source.getConnection()) {
      var f = fixture(c, "reseller");

      assertThrows(
          SQLException.class,
          () -> TestSql.execute(c, "DELETE FROM discrepancy_report WHERE id=?", f.report()));
      // What was captured at filing stays.
      for (String change :
          new String[] {
            "note='Edited'",
            "shortfall_total=0",
            "filed_by='owner'",
            "filed_at=now() + interval '1 day'",
            "order_id=order_id + 1",
            "idempotency_key=gen_random_uuid()",
            // Not a resolution: the status stays open.
            "resolution_note='Pre-written'"
          })
        assertThrows(
            SQLException.class,
            () ->
                TestSql.execute(
                    c, "UPDATE discrepancy_report SET " + change + " WHERE id=?", f.report()),
            change);
      assertEquals("open", value(c, "SELECT status AS v FROM discrepancy_report WHERE id=?", f.report()));
      assertEquals("", value(c, "SELECT note AS v FROM discrepancy_report WHERE id=?", f.report()));
    }
  }

  @Test
  void aReportIsResolvedOnceAndThenNeverChangesAgain() throws SQLException {
    try (Connection c = source.getConnection()) {
      var f = fixture(c, "reseller");

      assertEquals(
          1,
          TestSql.execute(
              c,
              "UPDATE discrepancy_report SET status='approved', resolved_by='owner',"
                  + " resolved_at=now(), resolution_note='Fine' WHERE id=?",
              f.report()));

      for (String change :
          new String[] {
            "status='declined', resolved_by='owner', resolved_at=now()",
            "resolution_note='Changed my mind'",
            "status='open', resolved_by=NULL, resolved_at=NULL",
            "note='Edited'"
          })
        assertThrows(
            SQLException.class,
            () ->
                TestSql.execute(
                    c, "UPDATE discrepancy_report SET " + change + " WHERE id=?", f.report()),
            change);
      assertThrows(
          SQLException.class,
          () -> TestSql.execute(c, "DELETE FROM discrepancy_report WHERE id=?", f.report()));
      assertEquals(
          "approved", value(c, "SELECT status AS v FROM discrepancy_report WHERE id=?", f.report()));
      assertEquals(
          "Fine", value(c, "SELECT resolution_note AS v FROM discrepancy_report WHERE id=?", f.report()));
    }
  }

  @Test
  void theResolverIsRecordedAndIsNeverTheFiler() throws SQLException {
    try (Connection c = source.getConnection()) {
      var byOwner = fixture(c, "owner");
      var byReseller = fixture(c, "reseller");

      // Resolved by the filer, resolved with no resolver or time, open with a resolver.
      assertThrows(
          SQLException.class,
          () ->
              TestSql.execute(
                  c,
                  "UPDATE discrepancy_report SET status='approved', resolved_by='owner',"
                      + " resolved_at=now() WHERE id=?",
                  byOwner.report()));
      assertThrows(
          SQLException.class,
          () ->
              TestSql.execute(
                  c,
                  "UPDATE discrepancy_report SET status='approved' WHERE id=?",
                  byReseller.report()));
      assertThrows(
          SQLException.class,
          () ->
              TestSql.execute(
                  c,
                  "UPDATE discrepancy_report SET resolved_by='owner', resolved_at=now() WHERE id=?",
                  byReseller.report()));
      assertThrows(
          SQLException.class,
          () ->
              TestSql.execute(
                  c,
                  "UPDATE discrepancy_report SET status='approved', resolved_by='reseller',"
                      + " resolved_at=now() WHERE id=?",
                  byReseller.report()));

      assertEquals(
          1,
          TestSql.execute(
              c,
              "UPDATE discrepancy_report SET status='declined', resolved_by='reseller',"
                  + " resolved_at=now() WHERE id=?",
              byOwner.report()));
    }
  }

  @Test
  void aSecondReportOnAnOrderAndAKeyUsedTwiceAreRefused() throws SQLException {
    try (Connection c = source.getConnection()) {
      var f = fixture(c, "reseller");
      var key = TestSql.one(c, "SELECT idempotency_key AS v FROM discrepancy_report WHERE id=?", f.report()).get("v");
      Order other = delivered(c, f.shop(), f.shop().rosa(), ham(f.shop(), "10"));

      assertThrows(
          SQLException.class,
          () ->
              TestSql.execute(
                  c,
                  "INSERT INTO discrepancy_report(tenant_id,order_id,filed_by,filed_at,"
                      + "shortfall_total,idempotency_key) VALUES (?,?,'owner',now(),1,?)",
                  f.shop().tenant(),
                  f.order().id(),
                  UUID.randomUUID()));
      assertThrows(
          SQLException.class,
          () ->
              TestSql.execute(
                  c,
                  "INSERT INTO discrepancy_report(tenant_id,order_id,filed_by,filed_at,"
                      + "shortfall_total,idempotency_key) VALUES (?,?,'owner',now(),1,?)",
                  f.shop().tenant(),
                  other.id(),
                  key));
    }
  }
}
