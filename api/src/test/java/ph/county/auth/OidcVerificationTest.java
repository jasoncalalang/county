package ph.county.auth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Exercises the real bearer verifier, not @TestSecurity or a production authentication bypass. */
@QuarkusTest
@TestProfile(OidcVerificationTest.RealOidcProfile.class)
@QuarkusTestResource(value = LocalOidcServer.class, restrictToAnnotatedClass = true)
public class OidcVerificationTest {
  LocalOidcServer server;

  public static class RealOidcProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.oidc.enabled", "true",
          "quarkus.oidc.application-type", "service",
          "quarkus.oidc.client-id", "county-oidc-test",
          "quarkus.oidc.token.audience", "county-oidc-test");
    }
  }

  private String token(
      String issuer, String audience, long expiry, boolean wrongKey, boolean subject) {
    return server.token(issuer, audience, expiry, wrongKey, subject);
  }

  private long future() {
    return Instant.now().plusSeconds(600).getEpochSecond();
  }

  private void rejected(String token) {
    given().basePath("").auth().oauth2(token).get("/api/me").then().statusCode(401);
  }

  @Test
  void validSignedTokenReachesOwnerEndpoint() {
    given()
        .basePath("")
        .auth()
        .oauth2(token(server.issuer(), "county-oidc-test", future(), false, true))
        .get("/api/me")
        .then()
        .statusCode(200)
        .body("subject", is("oidc-owner"));
  }

  @Test
  void wrongAudienceIsRejected() {
    rejected(token(server.issuer(), "different-client", future(), false, true));
  }

  @Test
  void wrongIssuerIsRejected() {
    rejected(token("https://untrusted.example", "county-oidc-test", future(), false, true));
  }

  @Test
  void invalidSignatureIsRejected() {
    rejected(token(server.issuer(), "county-oidc-test", future(), true, true));
  }

  @Test
  void expiredTokenIsRejected() {
    rejected(
        token(
            server.issuer(),
            "county-oidc-test",
            Instant.now().minusSeconds(600).getEpochSecond(),
            false,
            true));
  }

  @Test
  void missingSubjectIsRejected() {
    rejected(token(server.issuer(), "county-oidc-test", future(), false, false));
  }

  @Test
  void missingAndMalformedBearerAreRejected() {
    given().basePath("").get("/api/me").then().statusCode(401);
    rejected("not-a-jwt");
  }
}
