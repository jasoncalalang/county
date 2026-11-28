package ph.com.ridgewell.county.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static ph.com.ridgewell.county.api.DiscrepancyFixtures.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * PostgreSQL cannot store a NUL character in text, so one in a free-text field would be a server
 * error. Bad input is a 422 on its field, never a 500.
 */
@QuarkusTest
class NulCharacterApiTest {
  /** The JSON escape of NUL: the server decodes it to the character itself. */
  private static final String NUL = "\\u0000";

  private static final String MESSAGE = "Remove characters that cannot be stored";

  @Inject AgroalDataSource source;

  private Shop shop(String subject) throws SQLException {
    try (var c = source.getConnection()) {
      return DiscrepancyFixtures.shop(c, subject);
    }
  }

  private static void refused(Response response, String field) {
    response
        .then()
        .statusCode(422)
        .contentType("application/problem+json")
        .body("errors", hasKey(field))
        .body("errors.'" + field + "'", is(MESSAGE));
  }

  private static Response json(String method, String path, String body) {
    var request = given().basePath("").contentType("application/json").body(body);
    return switch (method) {
      case "POST" -> request.post(path);
      default -> request.put(path);
    };
  }

  @Test
  @TestSecurity(
      user = "nul-place",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "nul-place")})
  void anOrderNoteWithANulCharacterIsRefusedOnTheNote() throws SQLException {
    Shop s = shop("nul-place");
    refused(
        json(
            "POST",
            "/api/order/" + s.rosaToken() + "/orders",
            "{\"lines\":[{\"productId\":" + s.ham() + ",\"quantity\":1}],\"note\":\"x" + NUL
                + "y\",\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"),
        "note");
  }

  @Test
  @TestSecurity(
      user = "nul-quote",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "nul-quote")})
  void aQuoteNoteWithANulCharacterIsRefusedOnTheNote() throws SQLException {
    Shop s = shop("nul-quote");
    refused(
        json(
            "POST",
            "/api/quotes",
            "{\"resellerId\":" + s.rosa() + ",\"lines\":[{\"productId\":" + s.ham()
                + ",\"quantity\":1}],\"note\":\"x" + NUL + "y\",\"expiresOn\":\""
                + LocalDate.now().plusDays(3) + "\"}"),
        "note");
  }

  @Test
  @TestSecurity(
      user = "nul-report",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "nul-report")})
  void aReportNoteAndAResolutionNoteWithANulCharacterAreRefusedOnTheNote() throws SQLException {
    Shop s = shop("nul-report");
    Order o;
    Order resolving;
    try (var c = source.getConnection()) {
      o = delivered(c, s, s.rosa(), ham(s, "10"));
      resolving = delivered(c, s, s.rosa(), ham(s, "10"));
    }
    String line = line(s.ham(), "8");
    String nul = "x" + NUL + "y";

    refused(fileAsReseller(s.rosaToken(), o.id(), body(UUID.randomUUID(), nul, line)), "note");
    refused(fileAsOwner(o.id(), body(UUID.randomUUID(), nul, line)), "note");
    try (var c = source.getConnection()) {
      org.junit.jupiter.api.Assertions.assertEquals(
          0, DiscrepancyFixtures.reports(c, s.tenant(), o.id()));
    }

    fileAsReseller(s.rosaToken(), resolving.id(), body(line)).then().statusCode(201);
    for (String action : new String[] {"approve", "decline"})
      refused(resolveAsOwner(resolving.id(), action, "{\"note\":\"" + nul + "\"}"), "note");
    Order byOwner;
    try (var c = source.getConnection()) {
      byOwner = delivered(c, s, s.rosa(), ham(s, "10"));
    }
    fileAsOwner(byOwner.id(), body(line)).then().statusCode(201);
    for (String action : new String[] {"approve", "decline"})
      refused(
          resolveAsReseller(s.rosaToken(), byOwner.id(), action, "{\"note\":\"" + nul + "\"}"),
          "note");
    readAsOwner(resolving.id()).then().body("status", is("open"));
    readAsOwner(byOwner.id()).then().body("status", is("open"));
  }

  private static final String BAD = "A" + NUL + "B";
  private static final String DAYS = "\"cutoff\":\"16:00\",\"deliveryDays\":[1,2]";

  private static void business() {
    json("POST", "/api/tenants", "{\"name\":\"Nena\"," + DAYS + "}").then().statusCode(201);
  }

  @Test
  @TestSecurity(
      user = "nul-business",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "nul-business")})
  void aBusinessNameWithANulCharacterIsRefusedOnTheName() {
    refused(json("POST", "/api/tenants", "{\"name\":\"" + BAD + "\"," + DAYS + "}"), "name");
    business();
    refused(json("PUT", "/api/settings", "{\"name\":\"" + BAD + "\"," + DAYS + "}"), "name");
  }

  @Test
  @TestSecurity(
      user = "nul-product",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "nul-product")})
  void aProductNameWithANulCharacterIsRefusedOnTheName() {
    business();
    String rest = ",\"unit\":\"pack\",\"price\":120}";
    refused(json("POST", "/api/products", "{\"name\":\"" + BAD + "\"" + rest), "name");
    long product =
        json("POST", "/api/products", "{\"name\":\"Ham\"" + rest)
            .then().statusCode(201).extract().jsonPath().getLong("id");
    refused(json("PUT", "/api/products/" + product, "{\"name\":\"" + BAD + "\"" + rest), "name");
  }

  @Test
  @TestSecurity(
      user = "nul-reseller",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "nul-reseller")})
  void aResellerNameWithANulCharacterIsRefusedOnTheName() {
    business();
    String rest = ",\"mobile\":\"09171230001\"}";
    refused(json("POST", "/api/resellers", "{\"name\":\"" + BAD + "\"" + rest), "name");
    long reseller =
        json("POST", "/api/resellers", "{\"name\":\"Rosa\"" + rest)
            .then().statusCode(201).extract().jsonPath().getLong("id");
    refused(
        json("PUT", "/api/resellers/" + reseller, "{\"name\":\"" + BAD + "\"" + rest), "name");
  }

  @Test
  @TestSecurity(
      user = "nul-tier",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "nul-tier")})
  void aPriceTierNameWithANulCharacterIsRefusedOnTheName() {
    business();
    refused(json("POST", "/api/price-tiers", "{\"name\":\"" + BAD + "\"}"), "name");
    long tier =
        json("POST", "/api/price-tiers", "{\"name\":\"Gold\"}")
            .then().statusCode(201).extract().jsonPath().getLong("id");
    refused(json("PUT", "/api/price-tiers/" + tier, "{\"name\":\"" + BAD + "\"}"), "name");
  }

  @Test
  @TestSecurity(
      user = "nul-mobile",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "nul-mobile")})
  void aMobileNumberWithANulCharacterIsNotAServerError() {
    json("POST", "/api/tenants", "{\"name\":\"Nena\",\"cutoff\":\"16:00\",\"deliveryDays\":[1]}")
        .then()
        .statusCode(201);
    json("POST", "/api/resellers", "{\"name\":\"Rosa\",\"mobile\":\"0917" + NUL + "1230001\"}")
        .then()
        .statusCode(422)
        .body("errors", hasKey("mobile"));
  }
}
