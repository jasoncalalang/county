package ph.com.ridgewell.county.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static ph.com.ridgewell.county.api.DiscrepancyFixtures.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Scope comes from the owner's identity or the reseller's link, never from the request: another
 * business's owner, another reseller of the same business, a blocked or unknown link and an
 * anonymous caller reach no report.
 */
@QuarkusTest
class DiscrepancyIsolationTest {
  @Inject AgroalDataSource source;

  private Shop shop(String subject) throws SQLException {
    try (var c = source.getConnection()) {
      return DiscrepancyFixtures.shop(c, subject);
    }
  }

  private Order deliveredHam(Shop s, long reseller) throws SQLException {
    try (var c = source.getConnection()) {
      return delivered(c, s, reseller, ham(s, "10"));
    }
  }

  private long reports(Shop s, Order o) throws SQLException {
    try (var c = source.getConnection()) {
      return DiscrepancyFixtures.reports(c, s.tenant(), o.id());
    }
  }

  private String status(Order o) throws SQLException {
    try (var c = source.getConnection()) {
      return (String)
          TestSql.one(c, "SELECT status FROM discrepancy_report WHERE order_id=?", o.id())
              .get("status");
    }
  }

  @Test
  @TestSecurity(
      user = "disc-iso-b",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "disc-iso-b")})
  void anotherBusinessesOwnerFindsNoReportAndCannotFileOrResolve() throws SQLException {
    Shop a = shop("disc-iso-a");
    Order withReport = deliveredHam(a, a.rosa());
    Order without = deliveredHam(a, a.rosa());
    fileAsReseller(a.rosaToken(), withReport.id(), body(line(a.ham(), "8")))
        .then()
        .statusCode(201);
    Shop b = shop("disc-iso-b");
    fileAsReseller(b.rosaToken(), deliveredHam(b, b.rosa()).id(), body(line(b.ham(), "8")))
        .then()
        .statusCode(201);

    readAsOwner(withReport.id()).then().statusCode(404);
    readAsOwner(without.id()).then().statusCode(404);
    fileAsOwner(withReport.id(), body(line(a.ham(), "7"))).then().statusCode(404);
    fileAsOwner(without.id(), body(line(a.ham(), "7"))).then().statusCode(404);
    resolveAsOwner(withReport.id(), "approve", "{}").then().statusCode(404);
    resolveAsOwner(withReport.id(), "decline", "{}").then().statusCode(404);

    // B's own list holds B's report, never A's.
    listAsOwner("")
        .then()
        .statusCode(200)
        .body("$", hasSize(1))
        .body("orderId", not(hasItem((int) withReport.id())));
    assertEquals("open", status(withReport));
    assertEquals(0, reports(a, without));
  }

  @Test
  @TestSecurity(
      user = "disc-iso-lister",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "disc-iso-lister")})
  void theOwnersListHoldsOnlyTheirOwnBusinessesReports() throws SQLException {
    Shop mine = shop("disc-iso-lister");
    Shop other = shop("disc-iso-lister-other");
    Order mineOrder = deliveredHam(mine, mine.rosa());
    Order otherOrder = deliveredHam(other, other.rosa());
    fileAsReseller(mine.rosaToken(), mineOrder.id(), body(line(mine.ham(), "8")))
        .then()
        .statusCode(201);
    fileAsReseller(other.rosaToken(), otherOrder.id(), body(line(other.ham(), "8")))
        .then()
        .statusCode(201);

    listAsOwner("")
        .then()
        .statusCode(200)
        .body("orderId", contains((int) mineOrder.id()))
        .body("orderId", not(hasItem((int) otherOrder.id())));
    listAsOwner("?status=open").then().body("$", hasSize(1));
  }

  @Test
  @TestSecurity(
      user = "disc-iso-link",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "disc-iso-link")})
  void aLinkOfAnotherResellerAnotherBusinessOrAnUnknownOneReachesNothing() throws SQLException {
    Shop a = shop("disc-iso-link");
    Shop other = shop("disc-iso-link-other");
    Order reseller = deliveredHam(a, a.rosa());
    Order owner = deliveredHam(a, a.rosa());
    Order bare = deliveredHam(a, a.rosa());
    fileAsReseller(a.rosaToken(), reseller.id(), body(line(a.ham(), "8"))).then().statusCode(201);
    fileAsOwner(owner.id(), body(line(a.ham(), "8"))).then().statusCode(201);

    // Ben is a reseller of the same business, the other shop's Rosa is of another business.
    for (String token : new String[] {a.benToken(), other.rosaToken(), token(), "short"}) {
      readAsReseller(token, reseller.id()).then().statusCode(404);
      fileAsReseller(token, bare.id(), body(line(a.ham(), "7"))).then().statusCode(404);
      resolveAsReseller(token, owner.id(), "approve", "{}").then().statusCode(404);
      resolveAsReseller(token, owner.id(), "decline", "{}").then().statusCode(404);
    }

    assertEquals(0, reports(a, bare));
    assertEquals("open", status(owner));
    // Rosa's own link still works.
    readAsReseller(a.rosaToken(), reseller.id()).then().statusCode(200);
  }

  @Test
  @TestSecurity(
      user = "disc-iso-blocked",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "disc-iso-blocked")})
  void aBlockedLinkReachesNothingAndIsAnsweredLikeAnUnknownOne() throws SQLException {
    Shop s = shop("disc-iso-blocked");
    Order reseller = deliveredHam(s, s.rosa());
    Order owner = deliveredHam(s, s.rosa());
    Order bare = deliveredHam(s, s.rosa());
    fileAsReseller(s.rosaToken(), reseller.id(), body(line(s.ham(), "8"))).then().statusCode(201);
    fileAsOwner(owner.id(), body(line(s.ham(), "8"))).then().statusCode(201);
    try (var c = source.getConnection()) {
      TestSql.execute(c, "UPDATE reseller SET status='blocked' WHERE id=?", s.rosa());
    }

    var unknown = readAsReseller(token(), reseller.id());
    var blocked = readAsReseller(s.rosaToken(), reseller.id());
    blocked.then().statusCode(404);
    assertEquals(unknown.asString(), blocked.asString());
    fileAsReseller(s.rosaToken(), bare.id(), body(line(s.ham(), "7"))).then().statusCode(404);
    resolveAsReseller(s.rosaToken(), owner.id(), "approve", "{}").then().statusCode(404);
    resolveAsReseller(s.rosaToken(), owner.id(), "decline", "{}").then().statusCode(404);

    assertEquals(0, reports(s, bare));
    assertEquals("open", status(owner));
    // The owner still sees the report: only the link is blocked.
    readAsOwner(reseller.id()).then().statusCode(200);
  }

  @Test
  void anonymousCallersAreNotAuthenticatedOnTheOwnerEndpoints() {
    String file = body(UUID.randomUUID(), null, line(1, "1"));
    readAsOwner(1).then().statusCode(401);
    fileAsOwner(1, file).then().statusCode(401);
    resolveAsOwner(1, "approve", "{}").then().statusCode(401);
    resolveAsOwner(1, "decline", "{}").then().statusCode(401);
    listAsOwner("").then().statusCode(401);
    listAsOwner("?status=open").then().statusCode(401);
    // A reseller's token is not an owner's credential.
    given()
        .basePath("")
        .header("Authorization", "Bearer " + token())
        .get("/api/discrepancies")
        .then()
        .statusCode(401);
  }
}
