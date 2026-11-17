package ph.com.ridgewell.county.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import java.sql.Connection;
import org.junit.jupiter.api.Test;

/** Unique constraints surface as 409 problems on create and on edit, and roll back the edit. */
@QuarkusTest
class DuplicateRecordsApiTest {
  @Inject AgroalDataSource dataSource;

  private static String reseller(String name, String mobile) {
    return "{\"name\":\"" + name + "\",\"mobile\":\"" + mobile + "\"}";
  }

  private static io.restassured.specification.RequestSpecification api() {
    return given().basePath("").contentType("application/json");
  }

  @Test
  @TestSecurity(
      user = "duplicate-records",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "duplicate-records")})
  void duplicateMobileOrActiveProductNameIsAConflict() throws Exception {
    try (Connection c = dataSource.getConnection()) {
      TestOwners.createBusiness(c, "duplicate-records");
    }
    api().body(reseller("Rosa", "09171234567")).post("/api/resellers").then().statusCode(201);
    api()
        .body(reseller("Rosa again", "09171234567"))
        .post("/api/resellers")
        .then()
        .statusCode(409)
        .contentType("application/problem+json")
        .body("detail", equalTo("A record with those details already exists"));

    int ben =
        api()
            .body(reseller("Ben", "09181234567"))
            .post("/api/resellers")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    api()
        .body(reseller("Ben", "09171234567"))
        .put("/api/resellers/" + ben)
        .then()
        .statusCode(409)
        .body("detail", equalTo("A record with those details already exists"));
    api()
        .get("/api/resellers")
        .then()
        .statusCode(200)
        .body("find { it.id == " + ben + " }.mobile", equalTo("+639181234567"));

    String product = "{\"name\":\"Hamonado\",\"unit\":\"pack\",\"price\":120}";
    api()
        .body(product)
        .post("/api/products")
        .then()
        .statusCode(201)
        .body("price", equalTo(120.00f));
    api().body(product).post("/api/products").then().statusCode(409);
  }
}
