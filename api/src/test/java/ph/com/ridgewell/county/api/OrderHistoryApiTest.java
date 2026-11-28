package ph.com.ridgewell.county.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** W3: every successful order action leaves one event, with who did it and when. */
@QuarkusTest
class OrderHistoryApiTest {
  private static final String OWNER_NAME = "Nena Santos";

  @Inject AgroalDataSource dataSource;

  record Shop(long tenant, long ownerUser, long product, long reseller, String token) {}

  /** A business for the subject whose owner has a display name, one product and one reseller. */
  private Shop shop(String subject) throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      long tenant = TestOwners.createBusiness(c, subject);
      long owner =
          TestSql.id(TestSql.one(c, "SELECT owner_user_id AS id FROM business WHERE id=?", tenant));
      TestSql.execute(c, "UPDATE app_user SET display_name=? WHERE id=?", OWNER_NAME, owner);
      long product =
          TestSql.id(
              TestSql.one(
                  c,
                  "INSERT INTO product(tenant_id,name,unit,price) VALUES (?,'Hamonado','pack',120)"
                      + " RETURNING id",
                  tenant));
      String token = UUID.randomUUID().toString().replace("-", "").substring(0, 22);
      long reseller =
          TestSql.id(
              TestSql.one(
                  c,
                  "INSERT INTO reseller(tenant_id,name,mobile,token) VALUES (?,'Rosa',"
                      + "'+639171234567',?) RETURNING id",
                  tenant,
                  token));
      return new Shop(tenant, owner, product, reseller, token);
    }
  }

  private long place(Shop s) {
    return given()
        .basePath("")
        .contentType("application/json")
        .body(
            "{\"lines\":[{\"productId\":"
                + s.product
                + ",\"quantity\":1}],\"idempotencyKey\":\""
                + UUID.randomUUID()
                + "\"}")
        .post("/api/order/" + s.token + "/orders")
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getLong("id");
  }

  private JsonPath owner(long id, String action) {
    return given()
        .basePath("")
        .post("/api/orders/" + id + "/" + action)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath();
  }

  private JsonPath deliver(long id, String body) {
    return given()
        .basePath("")
        .contentType("application/json")
        .body(body)
        .post("/api/orders/" + id + "/deliver")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath();
  }

  private JsonPath detail(long id) {
    return given()
        .basePath("")
        .get("/api/orders/" + id)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath();
  }

  private void rejected(String method, String path, int status) {
    var request = given().basePath("");
    var response =
        method.equals("deliver")
            ? request.contentType("application/json").body("{\"paid\":false}").post(path)
            : request.post(path);
    response.then().statusCode(status);
  }

  private List<Map<String, Object>> rows(long tenant, long order) throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      return TestSql.rows(
          c,
          "SELECT actor_type, actor_user_id, actor_reseller_id FROM order_event"
              + " WHERE tenant_id=? AND order_id=? ORDER BY at, id",
          tenant,
          order);
    }
  }

  @Test
  @TestSecurity(
      user = "history-owner-actions",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "history-owner-actions")})
  void confirmDeliverAndMarkPaidRecordThreeOwnerEventsInOrder() throws SQLException {
    Shop s = shop("history-owner-actions");
    long id = place(s);
    assertEquals(List.of(), detail(id).getList("events"));

    assertEquals(1, owner(id, "confirm").getList("events").size());
    assertEquals(2, deliver(id, "{\"paid\":false}").getList("events").size());
    var done = owner(id, "mark-paid");

    for (var view : List.of(done, detail(id))) {
      assertEquals(3, view.getList("events").size());
      assertEquals(List.of("placed", "confirmed", "delivered"), view.getList("events.fromStatus"));
      assertEquals(
          List.of("confirmed", "delivered", "delivered"), view.getList("events.toStatus"));
      assertEquals(List.of(false, false, true), view.getList("events.paid"));
      assertEquals(List.of("owner", "owner", "owner"), view.getList("events.actorType"));
      assertEquals(List.of(OWNER_NAME, OWNER_NAME, OWNER_NAME), view.getList("events.actorName"));
      List<String> times = view.getList("events.at");
      for (int i = 1; i < times.size(); i++)
        assertFalse(Instant.parse(times.get(i)).isBefore(Instant.parse(times.get(i - 1))));
    }
    var stored = rows(s.tenant, id);
    assertEquals(3, stored.size());
    for (var row : stored) {
      assertEquals("owner", row.get("actor_type"));
      assertEquals(s.ownerUser, ((Number) row.get("actor_user_id")).longValue());
      assertNull(row.get("actor_reseller_id"));
    }
  }

  @Test
  @TestSecurity(
      user = "history-deliver-paid",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "history-deliver-paid")})
  void deliveringAsPaidIsOneEventThatIsAlsoPaid() throws SQLException {
    Shop s = shop("history-deliver-paid");
    long id = place(s);
    owner(id, "confirm");
    var view = deliver(id, "{\"paid\":true}");

    assertEquals(2, view.getList("events").size());
    assertEquals("confirmed", view.getString("events[1].fromStatus"));
    assertEquals("delivered", view.getString("events[1].toStatus"));
    assertEquals(true, view.getBoolean("events[1].paid"));
    assertEquals(2, rows(s.tenant, id).size());
  }

  @Test
  @TestSecurity(
      user = "history-cancel",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "history-cancel")})
  void resellerAndOwnerCancellationsRecordTheirOwnActor() throws SQLException {
    Shop s = shop("history-cancel");
    long byReseller = place(s);
    given()
        .basePath("")
        .post("/api/order/" + s.token + "/orders/" + byReseller + "/cancel")
        .then()
        .statusCode(200);

    var view = detail(byReseller);
    assertEquals(1, view.getList("events").size());
    assertEquals("reseller", view.getString("events[0].actorType"));
    assertEquals("Rosa", view.getString("events[0].actorName"));
    assertEquals("placed", view.getString("events[0].fromStatus"));
    assertEquals("cancelled", view.getString("events[0].toStatus"));
    var row = rows(s.tenant, byReseller).getFirst();
    assertEquals(s.reseller, ((Number) row.get("actor_reseller_id")).longValue());
    assertNull(row.get("actor_user_id"));

    long byOwner = place(s);
    owner(byOwner, "confirm");
    var cancelled = owner(byOwner, "cancel");
    assertEquals(2, cancelled.getList("events").size());
    assertEquals("owner", cancelled.getString("events[1].actorType"));
    assertEquals(OWNER_NAME, cancelled.getString("events[1].actorName"));
    assertEquals("cancelled", cancelled.getString("events[1].toStatus"));
    var last = rows(s.tenant, byOwner).getLast();
    assertEquals(s.ownerUser, ((Number) last.get("actor_user_id")).longValue());
    assertNull(last.get("actor_reseller_id"));
  }

  @Test
  @TestSecurity(
      user = "history-rejected",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "history-rejected")})
  void rejectedActionsLeaveNoEvent() throws SQLException {
    Shop s = shop("history-rejected");
    long id = place(s);
    // One valid action first, so "no new row" is compared with exactly one.
    assertEquals(1, owner(id, "confirm").getList("events").size());

    // The reseller cannot cancel a confirmed order.
    rejected("post", "/api/order/" + s.token + "/orders/" + id + "/cancel", 409);
    assertEquals(1, detail(id).getList("events").size());

    // The owner cannot deliver a placed order.
    long placed = place(s);
    rejected("deliver", "/api/orders/" + placed + "/deliver", 409);
    rejected("post", "/api/orders/" + placed + "/mark-paid", 409);
    assertEquals(List.of(), detail(placed).getList("events"));

    // A blocked link reaches nothing.
    given().basePath("").post("/api/resellers/" + s.reseller + "/block").then().statusCode(200);
    rejected("post", "/api/order/" + s.token + "/orders/" + placed + "/cancel", 404);
    assertEquals(List.of(), detail(placed).getList("events"));
    assertEquals(1, detail(id).getList("events").size());

    assertEquals(1, rows(s.tenant, id).size());
    assertEquals(0, rows(s.tenant, placed).size());
  }

  @Test
  @TestSecurity(
      user = "history-twice-paid",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "history-twice-paid")})
  void markingPaidTwiceFailsAndAddsNoEvent() throws SQLException {
    Shop s = shop("history-twice-paid");
    long id = place(s);
    owner(id, "confirm");
    deliver(id, "{\"paid\":false}");
    assertEquals(3, owner(id, "mark-paid").getList("events").size());

    rejected("post", "/api/orders/" + id + "/mark-paid", 409);

    assertEquals(3, detail(id).getList("events").size());
    assertEquals(3, rows(s.tenant, id).size());
  }

  @Test
  @TestSecurity(
      user = "history-forged-actor",
      roles = {"user"})
  @OidcSecurity(claims = {@Claim(key = "sub", value = "history-forged-actor")})
  void actorFieldsInTheBodyAreIgnoredAndTheSignedInOwnerIsRecorded() throws SQLException {
    Shop s = shop("history-forged-actor");
    long id = place(s);
    owner(id, "confirm");

    var view =
        deliver(
            id,
            "{\"paid\":false,\"actorType\":\"reseller\",\"actorUserId\":1,"
                + "\"actorResellerId\":"
                + s.reseller
                + ",\"actorName\":\"Mallory\"}");

    assertEquals(2, view.getList("events").size());
    assertEquals("owner", view.getString("events[1].actorType"));
    assertEquals(OWNER_NAME, view.getString("events[1].actorName"));
    var row = rows(s.tenant, id).getLast();
    assertEquals(s.ownerUser, ((Number) row.get("actor_user_id")).longValue());
    assertNull(row.get("actor_reseller_id"));
  }
}
