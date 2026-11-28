package ph.com.ridgewell.county.api;

import static io.restassured.RestAssured.given;

import io.restassured.response.Response;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Shops, orders and requests shared by the short-delivery report tests. */
final class DiscrepancyFixtures {
  private DiscrepancyFixtures() {}

  /**
   * A business with a pack at 120.00, a kg at 99.99, two kg products at 4,000,000,000.00 and two
   * resellers.
   */
  record Shop(
      long tenant,
      long ham,
      long rice,
      long saffron,
      long truffle,
      long rosa,
      String rosaToken,
      long ben,
      String benToken) {}

  record Line(long product, String name, String unit, String quantity, String price) {}

  record Order(long id, long number) {}

  static String token() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 22);
  }

  private static long product(Connection c, long tenant, String name, String unit, String price)
      throws SQLException {
    return TestSql.id(
        TestSql.one(
            c,
            "INSERT INTO product(tenant_id,name,unit,price) VALUES (?,?,?,?::numeric)"
                + " RETURNING id",
            tenant,
            name,
            unit,
            price));
  }

  private static long reseller(Connection c, long tenant, String name, String mobile, String token)
      throws SQLException {
    return TestSql.id(
        TestSql.one(
            c,
            "INSERT INTO reseller(tenant_id,name,mobile,token) VALUES (?,?,?,?) RETURNING id",
            tenant,
            name,
            mobile,
            token));
  }

  static Shop shop(Connection c, String subject) throws SQLException {
    long tenant = TestOwners.createBusiness(c, subject);
    String rosaToken = token();
    String benToken = token();
    return new Shop(
        tenant,
        product(c, tenant, "Hamonado", "pack", "120.00"),
        product(c, tenant, "Rice", "kg", "99.99"),
        product(c, tenant, "Saffron", "kg", "4000000000.00"),
        product(c, tenant, "Truffle", "kg", "4000000000.00"),
        reseller(c, tenant, "Rosa", "+639171234567", rosaToken),
        rosaToken,
        reseller(c, tenant, "Ben", "+639171234568", benToken),
        benToken);
  }

  static Line ham(Shop s, String quantity) {
    return new Line(s.ham(), "Hamonado", "pack", quantity, "120.00");
  }

  static Line rice(Shop s, String quantity) {
    return new Line(s.rice(), "Rice", "kg", quantity, "99.99");
  }

  static Line saffron(Shop s) {
    return new Line(s.saffron(), "Saffron", "kg", "1", "4000000000.00");
  }

  static Line truffle(Shop s) {
    return new Line(s.truffle(), "Truffle", "kg", "1", "4000000000.00");
  }

  /** An order the reseller placed, written as stored, in the given status. */
  static Order order(Connection c, Shop s, long reseller, String status, Line... lines)
      throws SQLException {
    BigDecimal total = new BigDecimal("0.00");
    for (Line l : lines)
      total =
          total.add(
              new BigDecimal(l.price()).multiply(new BigDecimal(l.quantity())).setScale(2, RoundingMode.HALF_UP));
    long number =
        ((Number)
                TestSql.one(
                        c,
                        "SELECT coalesce(max(number),0)+1 AS n FROM sales_order WHERE tenant_id=?",
                        s.tenant())
                    .get("n"))
            .longValue();
    long id =
        TestSql.id(
            TestSql.one(
                c,
                "INSERT INTO sales_order(tenant_id,reseller_id,number,placed_at,delivery_date,"
                    + "status,total,idempotency_key,delivered_at) VALUES (?,?,?,now(),current_date,"
                    + "?,?,?,CASE WHEN ? = 'delivered' THEN now() END) RETURNING id",
                s.tenant(),
                reseller,
                number,
                status,
                total,
                UUID.randomUUID(),
                status));
    for (Line l : lines)
      TestSql.execute(
          c,
          "INSERT INTO order_line(tenant_id,order_id,product_id,product_name,unit,quantity,price,"
              + "total) VALUES (?,?,?,?,?,?::numeric,?::numeric,?::numeric)",
          s.tenant(),
          id,
          l.product(),
          l.name(),
          l.unit(),
          l.quantity(),
          l.price(),
          new BigDecimal(l.price())
              .multiply(new BigDecimal(l.quantity()))
              .setScale(2, RoundingMode.HALF_UP));
    return new Order(id, number);
  }

  static Order delivered(Connection c, Shop s, long reseller, Line... lines) throws SQLException {
    return order(c, s, reseller, "delivered", lines);
  }

  static long reports(Connection c, long tenant, long order) throws SQLException {
    return ((Number)
            TestSql.one(
                    c,
                    "SELECT count(*) AS n FROM discrepancy_report WHERE tenant_id=? AND order_id=?",
                    tenant,
                    order)
                .get("n"))
        .longValue();
  }

  static String line(long product, String received) {
    return "{\"productId\":" + product + ",\"receivedQuantity\":" + received + "}";
  }

  static String body(UUID key, String note, String... lines) {
    return "{\"lines\":["
        + String.join(",", lines)
        + "],"
        + (note == null ? "" : "\"note\":\"" + note + "\",")
        + "\"idempotencyKey\":\""
        + key
        + "\"}";
  }

  static String body(String... lines) {
    return body(UUID.randomUUID(), null, lines);
  }

  static String repeated(String line, int times) {
    return Stream.generate(() -> line).limit(times).collect(Collectors.joining(","));
  }

  private static String reseller(String token, long order) {
    return "/api/order/" + token + "/orders/" + order + "/discrepancy";
  }

  private static String owner(long order) {
    return "/api/orders/" + order + "/discrepancy";
  }

  private static Response post(String path, String body) {
    var request = given().basePath("");
    return (body == null ? request : request.contentType("application/json").body(body))
        .post(path);
  }

  static Response fileAsReseller(String token, long order, String body) {
    return post(reseller(token, order), body);
  }

  static Response fileAsOwner(long order, String body) {
    return post(owner(order), body);
  }

  static Response readAsReseller(String token, long order) {
    return given().basePath("").get(reseller(token, order));
  }

  static Response readAsOwner(long order) {
    return given().basePath("").get(owner(order));
  }

  /** A resolution: {@code action} is approve or decline. A null body sends no body at all. */
  static Response resolveAsOwner(long order, String action, String body) {
    return post(owner(order) + "/" + action, body);
  }

  static Response resolveAsReseller(String token, long order, String action, String body) {
    return post(reseller(token, order) + "/" + action, body);
  }

  static Response listAsOwner(String query) {
    return given().basePath("").get("/api/discrepancies" + query);
  }
}
