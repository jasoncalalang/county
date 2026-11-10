package ph.com.ridgewell.county;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class HealthTest {

    @Test
    void readinessIncludesTheDatabase() {
        given().basePath("/")
                .when().get("/q/health/ready")
                .then().statusCode(200)
                .body("status", is("UP"))
                .body("checks.name", hasItem("Database connections health check"));
    }

    @Test
    void livenessIsServedOutsideTheApiPath() {
        given().basePath("/").when().get("/q/health/live").then().statusCode(200);
        given().basePath("/").when().get("/api/q/health/live").then().statusCode(404);
    }
}
