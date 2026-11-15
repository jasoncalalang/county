package ph.county.persistence;

import static ph.county.persistence.Database.*;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import ph.county.api.*;
import ph.county.domain.PhilippineMobile;

@ApplicationScoped
public class Owners {
  @Inject Database db;

  @ConfigProperty(name = "county.public-url")
  String publicUrl;

  private static final SecureRandom RANDOM = new SecureRandom();

  public static Map<String, Object> tenant(Connection c, String subject) throws SQLException {
    return one(c, "SELECT * FROM business WHERE owner_subject=?", subject);
  }

  public Map<String, Object> me(String subject) {
    return db.transaction(
        c -> {
          var tenants =
              rows(
                  c,
                  "SELECT id,name,cutoff,delivery_days FROM business WHERE owner_subject=?",
                  subject);
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("subject", subject);
          result.put("tenant", tenants.isEmpty() ? null : tenants.getFirst());
          return result;
        });
  }

  public Map<String, Object> create(String subject, Inputs.Business input) {
    return db.transaction(
        c ->
            one(
                c,
                "INSERT INTO business(owner_subject,name,cutoff,delivery_days) VALUES (?,?,?,?)"
                    + " RETURNING id,name,cutoff,delivery_days",
                subject,
                input.name().trim(),
                LocalTime.parse(input.cutoff()),
                c.createArrayOf("integer", input.deliveryDays().toArray())));
  }

  public Object settings(String subject, Inputs.Business input) {
    return db.transaction(
        c -> {
          long id = id(tenant(c, subject));
          if (input != null)
            execute(
                c,
                "UPDATE business SET name=?,cutoff=?,delivery_days=? WHERE id=?",
                input.name().trim(),
                LocalTime.parse(input.cutoff()),
                c.createArrayOf("integer", input.deliveryDays().toArray()),
                id);
          return one(c, "SELECT id,name,cutoff,delivery_days FROM business WHERE id=?", id);
        });
  }

  public Object products(String subject) {
    return db.transaction(
        c ->
            rows(
                c,
                "SELECT id,name,unit,price,active FROM product WHERE tenant_id=? ORDER BY active"
                    + " DESC,id",
                id(tenant(c, subject))));
  }

  public Object product(String subject, Long product, Inputs.Product input) {
    return db.transaction(
        c -> {
          long tenant = id(tenant(c, subject));
          if (product == null)
            return one(
                c,
                "INSERT INTO product(tenant_id,name,unit,price,active) VALUES (?,?,?,?,?) RETURNING"
                    + " id,name,unit,price,active",
                tenant,
                input.name().trim(),
                input.unit(),
                input.price(),
                input.active() == null || input.active());
          var old =
              one(
                  c,
                  "SELECT * FROM product WHERE tenant_id=? AND id=? FOR UPDATE",
                  tenant,
                  product);
          if (!old.get("unit").equals(input.unit())
              && !rows(
                      c,
                      "SELECT id FROM order_line WHERE tenant_id=? AND product_id=? LIMIT 1",
                      tenant,
                      product)
                  .isEmpty()) throw new Problem(409, "Unit cannot change after ordering");
          return one(
              c,
              "UPDATE product SET name=?,unit=?,price=?,active=? WHERE tenant_id=? AND id=?"
                  + " RETURNING id,name,unit,price,active",
              input.name().trim(),
              input.unit(),
              input.price(),
              input.active() == null ? old.get("active") : input.active(),
              tenant,
              product);
        });
  }

  public Object resellers(String subject) {
    return db.transaction(
        c ->
            rows(
                    c,
                    "SELECT r.id,r.name,r.mobile,r.status,r.token,(SELECT max(o.placed_at) FROM"
                        + " purchase_order o WHERE o.tenant_id=r.tenant_id AND o.reseller_id=r.id)"
                        + " last_order_at FROM reseller r WHERE r.tenant_id=? ORDER BY r.id",
                    id(tenant(c, subject)))
                .stream()
                .map(this::link)
                .toList());
  }

  public Object reseller(String subject, Long reseller, Inputs.Reseller input) {
    return db.transaction(
        c -> {
          long tenant = id(tenant(c, subject));
          String mobile = PhilippineMobile.normalize(input.mobile());
          if (reseller == null)
            return link(
                one(
                    c,
                    "INSERT INTO reseller(tenant_id,name,mobile,token) VALUES (?,?,?,?) RETURNING"
                        + " id,name,mobile,status,token",
                    tenant,
                    input.name().trim(),
                    mobile,
                    token()));
          return link(
              one(
                  c,
                  "UPDATE reseller SET name=?,mobile=? WHERE tenant_id=? AND id=? RETURNING"
                      + " id,name,mobile,status,token",
                  input.name().trim(),
                  mobile,
                  tenant,
                  reseller));
        });
  }

  public Object resellerAction(String subject, long reseller, String action) {
    return db.transaction(
        c -> {
          long tenant = id(tenant(c, subject));
          one(c, "SELECT id FROM reseller WHERE tenant_id=? AND id=? FOR UPDATE", tenant, reseller);
          switch (action) {
            case "block" ->
                execute(
                    c,
                    "UPDATE reseller SET status='blocked' WHERE tenant_id=? AND id=?",
                    tenant,
                    reseller);
            case "unblock" ->
                execute(
                    c,
                    "UPDATE reseller SET status=CASE WHEN has_ordered THEN 'active' ELSE 'invited'"
                        + " END WHERE tenant_id=? AND id=?",
                    tenant,
                    reseller);
            case "reissue-link" ->
                execute(
                    c,
                    "UPDATE reseller SET token=? WHERE tenant_id=? AND id=?",
                    token(),
                    tenant,
                    reseller);
            default -> throw new Problem(404, "Not found");
          }
          return link(
              one(
                  c,
                  "SELECT id,name,mobile,status,token FROM reseller WHERE tenant_id=? AND id=?",
                  tenant,
                  reseller));
        });
  }

  private Map<String, Object> link(Map<String, Object> row) {
    row.put("orderLink", publicUrl.replaceAll("/$", "") + "/order/" + row.get("token"));
    return row;
  }

  private static String token() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
