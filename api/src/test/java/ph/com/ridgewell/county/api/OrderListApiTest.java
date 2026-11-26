package ph.com.ridgewell.county.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The order list carries what a dashboard row shows: reseller name and a summary of lines. */
@QuarkusTest
class OrderListApiTest {
  @Inject AgroalDataSource source;

  private static String place(String token, String lines) {
    return given()
        .basePath("")
        .contentType("application/json")
        .body("{\"lines\":" + lines + ",\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}")
        .post("/api/order/" + token + "/orders")
        .then()
        .statusCode(201)
        .extract()
        .path("deliveryDate");
  }

  @Test
  @TestSecurity(
      user = "order-list",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "order-list")})
  void listShowsResellerNameAndLineSummaries() throws SQLException {
    long hamonado, tocino;
    String rosa = UUID.randomUUID().toString().replace("-", "").substring(0, 22);
    String ben = UUID.randomUUID().toString().replace("-", "").substring(0, 22);
    try (var c = source.getConnection()) {
      long tenant = TestOwners.createBusiness(c, "order-list");
      hamonado =
          TestSql.id(
              TestSql.one(
                  c,
                  "INSERT INTO product(tenant_id,name,unit,price) VALUES (?,'Hamonado','pack',120)"
                      + " RETURNING id",
                  tenant));
      tocino =
          TestSql.id(
              TestSql.one(
                  c,
                  "INSERT INTO product(tenant_id,name,unit,price) VALUES (?,'Tocino','kg',320)"
                      + " RETURNING id",
                  tenant));
      TestSql.execute(
          c,
          "INSERT INTO reseller(tenant_id,name,mobile,token) VALUES (?,'Aling Rosa','+639171234567',?)",
          tenant,
          rosa);
      TestSql.execute(
          c,
          "INSERT INTO reseller(tenant_id,name,mobile,token) VALUES (?,'Mang Ben','+639171234568',?)",
          tenant,
          ben);
    }
    String date =
        place(
            rosa,
            "[{\"productId\":"
                + tocino
                + ",\"quantity\":5},{\"productId\":"
                + hamonado
                + ",\"quantity\":10}]");
    place(ben, "[{\"productId\":" + hamonado + ",\"quantity\":20}]");

    given()
        .basePath("")
        .queryParam("date", date)
        .get("/api/orders")
        .then()
        .statusCode(200)
        .body("size()", is(2))
        .body("[0].resellerName", is("Aling Rosa"))
        .body("[0].items.productName", contains("Hamonado", "Tocino"))
        .body("[0].items.unit", contains("pack", "kg"))
        .body("[0].items.quantity", contains(10, 5))
        .body("[1].resellerName", is("Mang Ben"))
        .body("[1].items.productName", contains("Hamonado"))
        .body("[1].items.quantity", contains(20));

    int id =
        given()
            .basePath("")
            .queryParam("date", date)
            .get("/api/orders")
            .then()
            .extract()
            .path("[0].id");
    given()
        .basePath("")
        .get("/api/orders/" + id)
        .then()
        .statusCode(200)
        .body("resellerName", is("Aling Rosa"));
  }

  @Test
  @TestSecurity(
      user = "order-list-empty",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "order-list-empty")})
  void emptyListIsAnEmptyArray() throws SQLException {
    try (var c = source.getConnection()) {
      TestOwners.createBusiness(c, "order-list-empty");
    }
    given().basePath("").get("/api/orders").then().statusCode(200).body("size()", is(0));
  }
}
