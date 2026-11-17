package ph.com.ridgewell.county.persistence;

import static ph.com.ridgewell.county.persistence.Database.*;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import ph.com.ridgewell.county.api.*;
import ph.com.ridgewell.county.domain.*;

@ApplicationScoped
public class Orders {
  @Inject Database db;
  private static final ZoneId ZONE = ZoneId.of("Asia/Manila");

  public record Placement(boolean replay, Map<String, Object> order) {}

  private static Map<String, Object> reseller(Connection c, String token, boolean lock)
      throws SQLException {
    if (token == null || !token.matches("[A-Za-z0-9_-]{22}")) throw new Problem(404, "Not found");
    return one(
        c,
        "SELECT * FROM reseller WHERE token=? AND status<>'blocked'" + (lock ? " FOR UPDATE" : ""),
        token);
  }

  private static long tenantId(Map<String, Object> reseller) {
    return ((Number) reseller.get("tenantId")).longValue();
  }

  private static LocalDate delivery(Map<String, Object> business) {
    @SuppressWarnings("unchecked")
    var days = (List<Number>) business.get("deliveryDays");
    Set<DayOfWeek> weekdays = new HashSet<>();
    for (Number d : days) weekdays.add(DayOfWeek.of(d.intValue()));
    return DeliveryDates.compute(
        Instant.now(), ZONE, LocalTime.parse((String) business.get("cutoff")), weekdays);
  }

  private static Map<String, Object> order(Connection c, long tenant, long id, Long reseller)
      throws SQLException {
    var order =
        one(
            c,
            "SELECT * FROM purchase_order WHERE tenant_id=? AND id=?"
                + (reseller == null ? "" : " AND reseller_id=?"),
            reseller == null ? new Object[] {tenant, id} : new Object[] {tenant, id, reseller});
    order.remove("tenantId");
    order.remove("idempotencyKey");
    order.put(
        "lines",
        rows(
            c,
            "SELECT product_id,product_name,unit,quantity,price,total FROM order_line WHERE"
                + " tenant_id=? AND order_id=? ORDER BY id",
            tenant,
            id));
    return order;
  }

  public Object catalog(String token) {
    return db.transaction(
        c -> {
          var reseller = reseller(c, token, false);
          long tenant = tenantId(reseller);
          var business = one(c, "SELECT * FROM business WHERE id=?", tenant);
          return Map.of(
              "businessName",
              business.get("name"),
              "resellerName",
              reseller.get("name"),
              "deliveryDate",
              delivery(business).toString(),
              "cutoff",
              business.get("cutoff"),
              "products",
              rows(
                  c,
                  "SELECT id,name,unit,price FROM product WHERE tenant_id=? AND active ORDER BY id",
                  tenant),
              "recentOrders",
              rows(
                  c,
                  "SELECT id,number,delivery_date,total,status,placed_at FROM purchase_order WHERE"
                      + " tenant_id=? AND reseller_id=? ORDER BY placed_at DESC,id DESC LIMIT 10",
                  tenant,
                  id(reseller)));
        });
  }

  public Object getPublic(String token, long id) {
    return db.transaction(
        c -> {
          var r = reseller(c, token, false);
          return order(c, tenantId(r), id, id(r));
        });
  }

  public Placement place(String token, Inputs.Place input) {
    return db.transaction(
        c -> {
          var reseller = reseller(c, token, true);
          long tenant = tenantId(reseller), resellerId = id(reseller);
          // Serializes tenant numbering and idempotency checks in one transaction, without gaps on
          // rollback.
          var business = one(c, "SELECT * FROM business WHERE id=? FOR UPDATE", tenant);
          var existing =
              rows(
                  c,
                  "SELECT id,reseller_id FROM purchase_order WHERE tenant_id=? AND"
                      + " idempotency_key=?",
                  tenant,
                  input.idempotencyKey());
          if (!existing.isEmpty()) {
            var old = existing.getFirst();
            if (((Number) old.get("resellerId")).longValue() != resellerId)
              throw new Problem(409, "Idempotency key already used");
            return new Placement(true, order(c, tenant, id(old), resellerId));
          }
          Set<Long> seen = new HashSet<>();
          List<Map<String, Object>> lines = new ArrayList<>();
          BigDecimal total = new BigDecimal("0.00");
          // Stable product lock order avoids deadlocks between multi-line orders and edits.
          for (var line :
              input.lines().stream()
                  .sorted(Comparator.comparing(Inputs.Line::productId))
                  .toList()) {
            if (!seen.add(line.productId()))
              throw new Problem(422, "Duplicate product lines are not allowed");
            var product =
                one(
                    c,
                    "SELECT id,name,unit,price,active FROM product WHERE tenant_id=? AND id=? FOR"
                        + " SHARE",
                    tenant,
                    line.productId());
            if (!Boolean.TRUE.equals(product.get("active")))
              throw new Problem(422, product.get("name") + " is no longer available");
            var lineTotal = Money.line((BigDecimal) product.get("price"), line.quantity());
            total = total.add(lineTotal);
            if (total.compareTo(new BigDecimal("9999999999.99")) > 0)
              throw new Problem(422, "Order total is too large");
            product.put("quantity", line.quantity());
            product.put("total", lineTotal);
            lines.add(product);
          }
          long number = ((Number) business.get("nextOrderNumber")).longValue();
          var saved =
              one(
                  c,
                  "INSERT INTO"
                      + " purchase_order(tenant_id,reseller_id,number,placed_at,delivery_date,total,note,idempotency_key)"
                      + " VALUES (?,?,?,now(),?,?,?,?) RETURNING id",
                  tenant,
                  resellerId,
                  number,
                  delivery(business),
                  total,
                  input.note() == null ? "" : input.note(),
                  input.idempotencyKey());
          long id = id(saved);
          for (var line : lines)
            execute(
                c,
                "INSERT INTO"
                    + " order_line(tenant_id,order_id,product_id,product_name,unit,quantity,price,total)"
                    + " VALUES (?,?,?,?,?,?,?,?)",
                tenant,
                id,
                line.get("id"),
                line.get("name"),
                line.get("unit"),
                line.get("quantity"),
                line.get("price"),
                line.get("total"));
          execute(
              c, "UPDATE business SET next_order_number=next_order_number+1 WHERE id=?", tenant);
          execute(
              c,
              "UPDATE reseller SET has_ordered=true,status='active' WHERE tenant_id=? AND id=?",
              tenant,
              resellerId);
          return new Placement(false, order(c, tenant, id, resellerId));
        });
  }

  public Object get(String subject, long id) {
    return db.transaction(c -> order(c, id(Owners.tenant(c, subject)), id, null));
  }

  public Object list(String subject, String date, String status) {
    return db.transaction(
        c -> {
          long tenant = id(Owners.tenant(c, subject));
          List<Object> args = new ArrayList<>();
          args.add(tenant);
          String sql =
              "SELECT id,number,reseller_id,placed_at,delivery_date,status,total,paid,note FROM"
                  + " purchase_order WHERE tenant_id=?";
          if (date != null) {
            args.add(LocalDate.parse(date));
            sql += " AND delivery_date=?";
          }
          if (status != null) {
            args.add(statusValue(status).name().toLowerCase(Locale.ROOT));
            sql += " AND status=?";
          }
          return rows(c, sql + " ORDER BY number", args.toArray());
        });
  }

  private static OrderRules.Status statusValue(String value) {
    try {
      return OrderRules.Status.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new Problem(422, "Unknown status");
    }
  }

  public Object transition(
      String subject, String token, long orderId, String target, boolean paid) {
    return db.transaction(
        c -> {
          long tenant;
          Long resellerId = null;
          if (token != null) {
            var reseller = reseller(c, token, true);
            tenant = tenantId(reseller);
            resellerId = id(reseller);
          } else tenant = id(Owners.tenant(c, subject));
          var old =
              one(
                  c,
                  "SELECT * FROM purchase_order WHERE tenant_id=? AND id=?"
                      + (resellerId == null ? "" : " AND reseller_id=?")
                      + " FOR UPDATE",
                  resellerId == null
                      ? new Object[] {tenant, orderId}
                      : new Object[] {tenant, orderId, resellerId});
          String current = (String) old.get("status");
          if (target.equals("paid")) {
            if (!current.equals("delivered") || Boolean.TRUE.equals(old.get("paid")))
              throw new Problem(409, "Order is now " + current);
            execute(
                c,
                "UPDATE purchase_order SET paid=true,paid_at=now() WHERE tenant_id=? AND id=?",
                tenant,
                orderId);
          } else {
            try {
              OrderRules.transition(
                  statusValue(current),
                  statusValue(target),
                  token == null ? OrderRules.Actor.OWNER : OrderRules.Actor.RESELLER);
            } catch (IllegalArgumentException e) {
              throw new Problem(409, "Order is now " + current);
            }
            // Field name is chosen only from a closed internal state set, never request SQL.
            String field =
                switch (target) {
                  case "confirmed" -> "confirmed_at";
                  case "delivered" -> "delivered_at";
                  case "cancelled" -> "cancelled_at";
                  default -> throw new Problem(409, "Invalid transition");
                };
            execute(
                c,
                "UPDATE purchase_order SET status=?,"
                    + field
                    + "=now()"
                    + (target.equals("delivered")
                        ? ",paid=?,paid_at=CASE WHEN ? THEN now() ELSE NULL END"
                        : "")
                    + " WHERE tenant_id=? AND id=?",
                target.equals("delivered")
                    ? new Object[] {target, paid, paid, tenant, orderId}
                    : new Object[] {target, tenant, orderId});
          }
          return order(c, tenant, orderId, resellerId);
        });
  }

  public Object production(String subject, String date) {
    if (date == null) throw new Problem(422, "Delivery date is required");
    return db.transaction(
        c -> {
          long tenant = id(Owners.tenant(c, subject));
          LocalDate delivery = LocalDate.parse(date);
          return Map.of(
              "date",
              date,
              "lines",
              rows(
                  c,
                  "SELECT l.product_id,p.name product_name,l.unit,sum(l.quantity)"
                      + " quantity,count(DISTINCT o.id) order_count FROM order_line l JOIN"
                      + " purchase_order o ON o.id=l.order_id AND o.tenant_id=l.tenant_id JOIN"
                      + " product p ON p.tenant_id=l.tenant_id AND p.id=l.product_id WHERE"
                      + " o.tenant_id=? AND o.delivery_date=? AND o.status='confirmed' GROUP BY"
                      + " l.product_id,p.name,l.unit ORDER BY l.product_id",
                  tenant,
                  delivery),
              "unconfirmedCount",
              one(
                      c,
                      "SELECT count(*) count FROM purchase_order WHERE tenant_id=? AND"
                          + " delivery_date=? AND status='placed'",
                      tenant,
                      delivery)
                  .get("count"));
        });
  }
}
