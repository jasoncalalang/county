package ph.county.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OrderingApiTest {
  @Test
  @TestSecurity(
      user = "owner-a",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "owner-a")})
  void completeOrderingLoopAndReplay() {
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"name\":\"Nena\",\"cutoff\":\"16:00\",\"deliveryDays\":[1,2,3,4,5,6]}")
        .post("/api/tenants")
        .then()
        .statusCode(201);
    int product =
        given()
            .basePath("")
            .contentType("application/json")
            .body("{\"name\":\"Hamonado\",\"unit\":\"pack\",\"price\":120}")
            .post("/api/products")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    var reseller =
        given()
            .basePath("")
            .contentType("application/json")
            .body("{\"name\":\"Rosa\",\"mobile\":\"09171234567\"}")
            .post("/api/resellers")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath();
    String token = reseller.getString("token");
    String order =
        "{\"lines\":[{\"productId\":"
            + product
            + ",\"quantity\":10}],\"note\":\"before"
            + " 7am\",\"idempotencyKey\":\"8a783173-36c7-484f-97b3-efc5f4a4acbb\"}";
    int id =
        given()
            .basePath("")
            .contentType("application/json")
            .body(order)
            .post("/api/order/" + token + "/orders")
            .then()
            .statusCode(201)
            .body("total", is(1200.0f))
            .body("number", is(1))
            .extract()
            .path("id");
    given()
        .basePath("")
        .contentType("application/json")
        .body(order)
        .post("/api/order/" + token + "/orders")
        .then()
        .statusCode(200)
        .body("id", is(id));
    given()
        .basePath("")
        .post("/api/orders/" + id + "/confirm")
        .then()
        .statusCode(200)
        .body("status", is("confirmed"));
    given()
        .basePath("")
        .post("/api/order/" + token + "/orders/" + id + "/cancel")
        .then()
        .statusCode(409);
    given()
        .basePath("")
        .contentType("application/json")
        .body("{\"paid\":false}")
        .post("/api/orders/" + id + "/deliver")
        .then()
        .statusCode(200)
        .body("paid", is(false));
    given()
        .basePath("")
        .post("/api/orders/" + id + "/mark-paid")
        .then()
        .statusCode(200)
        .body("paid", is(true));
    given()
        .basePath("")
        .post("/api/resellers/" + reseller.getInt("id") + "/block")
        .then()
        .statusCode(200);
    given().basePath("").get("/api/order/" + token).then().statusCode(404);
  }

  @Test
  void ownerEndpointsRequireAuthentication() {
    given().basePath("").get("/api/products").then().statusCode(401);
  }
}
