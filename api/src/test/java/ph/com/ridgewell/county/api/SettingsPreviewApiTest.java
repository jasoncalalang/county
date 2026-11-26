package ph.com.ridgewell.county.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import ph.com.ridgewell.county.domain.DeliveryDates;

/** Settings and set-up forms preview the delivery date for unsaved values. */
@QuarkusTest
class SettingsPreviewApiTest {
  private static final ZoneId MANILA = ZoneId.of("Asia/Manila");

  @Test
  @TestSecurity(
      user = "preview-no-business",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "preview-no-business")})
  void previewsDeliveryDateForUnsavedValuesWithoutABusiness() {
    var days = EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY);
    String before =
        DeliveryDates.compute(Instant.now(), MANILA, LocalTime.of(16, 0), days).toString();
    String date =
        given()
            .basePath("")
            .queryParam("cutoff", "16:00")
            .queryParam("deliveryDays", 1, 4)
            .get("/api/settings/preview")
            .then()
            .statusCode(200)
            .body("timeZone", is("Asia/Manila"))
            .extract()
            .path("deliveryDate");
    String after =
        DeliveryDates.compute(Instant.now(), MANILA, LocalTime.of(16, 0), days).toString();
    org.junit.jupiter.api.Assertions.assertTrue(date.equals(before) || date.equals(after), date);
  }

  @Test
  @TestSecurity(
      user = "preview-invalid",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "preview-invalid")})
  void invalidValuesAreFieldErrors() {
    given()
        .basePath("")
        .queryParam("cutoff", "25:00")
        .queryParam("deliveryDays", 1)
        .get("/api/settings/preview")
        .then()
        .statusCode(422)
        .contentType("application/problem+json")
        .body("errors", hasKey("cutoff"));
    given()
        .basePath("")
        .queryParam("cutoff", "16:00")
        .get("/api/settings/preview")
        .then()
        .statusCode(422)
        .body("errors", hasKey("deliveryDays"));
    for (String day : new String[] {"0", "8", "monday"})
      given()
          .basePath("")
          .queryParam("cutoff", "16:00")
          .queryParam("deliveryDays", day)
          .get("/api/settings/preview")
          .then()
          .statusCode(422)
          .body("errors", hasKey("deliveryDays"));
  }

  @Test
  void previewRequiresSignIn() {
    given()
        .basePath("")
        .queryParam("cutoff", "16:00")
        .queryParam("deliveryDays", 1)
        .get("/api/settings/preview")
        .then()
        .statusCode(401);
  }
}
