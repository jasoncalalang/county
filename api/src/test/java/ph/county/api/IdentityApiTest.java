package ph.county.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class IdentityApiTest {
  @Inject AgroalDataSource source;

  @Test
  @TestSecurity(
      user = "identity-first",
      roles = {"user"})
  @OidcSecurity(
      claims = {
        @Claim(key = "sub", value = "identity-first"),
        @Claim(key = "name", value = "County Owner"),
        @Claim(key = "email", value = "owner@example.test")
      })
  void firstSignInPersistsOneUserAndIdentityBeforeBusinessSetup() throws Exception {
    long id =
        given()
            .basePath("")
            .get("/api/me")
            .then()
            .statusCode(200)
            .body("tenant", nullValue())
            .body("user.displayName", equalTo("County Owner"))
            .body("user.email", equalTo("owner@example.test"))
            .extract()
            .jsonPath()
            .getLong("user.id");
    given().basePath("").get("/api/me").then().statusCode(200).body("user.id", equalTo((int) id));
    try (var c = source.getConnection();
        var s =
            c.prepareStatement(
                "SELECT count(*) FROM external_identity WHERE provider='google' AND"
                    + " subject='identity-first' AND user_id=?")) {
      s.setLong(1, id);
      try (var rs = s.executeQuery()) {
        rs.next();
        assertEquals(1, rs.getLong(1));
      }
    }
  }

  @Test
  @TestSecurity(
      user = "identity-business",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "identity-business")})
  void businessIsOwnedByPersistedUserAndSecondCreationConflicts() {
    long user =
        given()
            .basePath("")
            .get("/api/me")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getLong("user.id");
    String body =
        "{\"name\":\"Identity business\",\"cutoff\":\"16:00\",\"deliveryDays\":[1,2,3,4,5]}";
    long tenant =
        given()
            .basePath("")
            .contentType("application/json")
            .body(body)
            .post("/api/tenants")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getLong("id");
    given()
        .basePath("")
        .get("/api/me")
        .then()
        .statusCode(200)
        .body("user.id", equalTo((int) user))
        .body("tenant.id", equalTo((int) tenant));
    given()
        .basePath("")
        .contentType("application/json")
        .body(body)
        .post("/api/tenants")
        .then()
        .statusCode(409);
  }
}
