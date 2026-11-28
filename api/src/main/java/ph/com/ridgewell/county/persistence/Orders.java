package ph.com.ridgewell.county.persistence;

import static jakarta.persistence.LockModeType.NONE;
import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import ph.com.ridgewell.county.api.*;
import ph.com.ridgewell.county.domain.*;

/**
 * Ordering. Lock order, everywhere: reseller, then business, then products by ascending id,
 * then the order (ADR 0013). Rows outside those four come after them, in the order the lock note
 * of api/README.md lists (ADR 0015).
 */
@ApplicationScoped
public class Orders {
  static final ZoneId ZONE = ZoneId.of("Asia/Manila");

  @Inject OrderHistory history;
  @Inject Pricing pricing;

  public record Placement(boolean replay, Views.Order order) {}

  static Reseller reseller(String token, LockModeType lock) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{22}")) throw new Problem(404, "Not found");
    return Reseller.byToken(token, Times.now(), lock).orElseThrow(() -> new Problem(404, "Not found"));
  }

  static LocalDate delivery(Business business) {
    return delivery(business.cutoff, business.deliveryDays);
  }

  /** The delivery date of an order placed now, in the business timezone. */
  static LocalDate delivery(LocalTime cutoff, Integer[] deliveryDays) {
    Set<DayOfWeek> weekdays = new HashSet<>();
    for (Integer d : deliveryDays) weekdays.add(DayOfWeek.of(d));
    return DeliveryDates.compute(Instant.now(), ZONE, cutoff, weekdays);
  }

  private static LocalDate date(String value) {
    try {
      return LocalDate.parse(value);
    } catch (DateTimeException | NullPointerException e) {
      throw Problem.field("date", "Enter a date such as 2026-11-05");
    }
  }

  /** Per-product tier prices for a reseller, empty when the reseller has no tier. */
  private static Map<Long, BigDecimal> tierPrices(Reseller reseller) {
    if (reseller.priceTierId == null) return Map.of();
    Map<Long, BigDecimal> prices = new HashMap<>();
    for (ProductTierPrice p :
        ProductTierPrice.<ProductTierPrice>list(
            "tenantId = ?1 and priceTierId = ?2", reseller.tenantId, reseller.priceTierId))
      prices.put(p.productId, p.price);
    return prices;
  }

  @Transactional
  public Views.Catalog catalog(String token) {
    var reseller = reseller(token, NONE);
    Business business = Business.findById(reseller.tenantId);
    Map<Long, BigDecimal> tierPrices = tierPrices(reseller);
    return new Views.Catalog(
        business.name,
        reseller.name,
        delivery(business).toString(),
        Times.clock(business.cutoff),
        Product.<Product>list(
                "tenantId = ?1 and active = true", Sort.by("id"), reseller.tenantId)
            .stream()
            .map(
                p ->
                    new Views.CatalogProduct(
                        p.id, p.name, p.unit, tierPrices.getOrDefault(p.id, p.price)))
            .toList(),
        SalesOrder.<SalesOrder>find(
                "tenantId = ?1 and resellerId = ?2",
                Sort.descending("placedAt", "id"),
                reseller.tenantId,
                reseller.id)
            .range(0, 9)
            .list()
            .stream()
            .map(
                o ->
                    new Views.RecentOrder(
                        o.id,
                        o.number,
                        o.deliveryDate.toString(),
                        o.total,
                        o.status,
                        Times.instant(o.placedAt)))
            .toList());
  }

  @Transactional
  public Views.Order getPublic(String token, long id) {
    var reseller = reseller(token, NONE);
    return resellerView(order(reseller.tenantId, id, reseller.id, NONE));
  }

  @Transactional
  public Placement place(String token, Inputs.Place input) {
    var reseller = reseller(token, PESSIMISTIC_WRITE);
    // The business lock serializes numbering and the idempotency check for this business.
    Business business = Business.findById(reseller.tenantId, PESSIMISTIC_WRITE);
    var existing =
        SalesOrder.<SalesOrder>find(
                "tenantId = ?1 and idempotencyKey = ?2",
                reseller.tenantId,
                input.idempotencyKey())
            .singleResultOptional();
    if (existing.isPresent()) {
      // A key used by another reseller of this business must not reveal that order.
      if (existing.get().resellerId != reseller.id)
        throw new Problem(409, "Idempotency key already used");
      return new Placement(true, resellerView(existing.get()));
    }
    var priced = pricing.price(reseller, input.lines());
    List<OrderLine> lines = new ArrayList<>();
    for (var p : priced.lines()) {
      var saved = new OrderLine();
      saved.tenantId = reseller.tenantId;
      saved.productId = p.product().id;
      saved.productName = p.product().name;
      saved.unit = p.product().unit;
      saved.quantity = p.quantity();
      saved.price = p.price();
      saved.total = p.total();
      lines.add(saved);
    }
    var order = new SalesOrder();
    order.tenantId = reseller.tenantId;
    order.resellerId = reseller.id;
    order.number = business.nextOrderNumber++;
    order.placedAt = Times.now();
    order.deliveryDate = delivery(business);
    order.total = priced.total();
    order.note = input.note() == null ? "" : input.note();
    order.idempotencyKey = input.idempotencyKey();
    order.persist();
    for (var line : lines) {
      line.orderId = order.id;
      line.persist();
    }
    reseller.hasOrdered = true;
    reseller.status = "active";
    SalesOrder.flush();
    return new Placement(false, view(order, lines));
  }

  @Transactional
  public Views.Order get(String subject, long id) {
    return ownerView(order(Owners.tenant(subject).id, id, null, NONE));
  }

  @Transactional
  public List<Views.OrderSummary> list(String subject, String date, String status) {
    StringBuilder query = new StringBuilder("tenantId = :tenant");
    Map<String, Object> params = new HashMap<>();
    params.put("tenant", Owners.tenant(subject).id);
    if (date != null) {
      query.append(" and deliveryDate = :date");
      params.put("date", date(date));
    }
    if (status != null) {
      query.append(" and status = :status");
      params.put("status", statusValue(status).name().toLowerCase(Locale.ROOT));
    }
    long tenant = (Long) params.get("tenant");
    List<SalesOrder> found =
        SalesOrder.<SalesOrder>list(query.toString(), Sort.by("number"), params);
    if (found.isEmpty()) return List.of();
    List<Long> ids = found.stream().map(o -> o.id).toList();
    Map<Long, List<Views.OrderItem>> items = new HashMap<>();
    for (OrderLine l :
        OrderLine.<OrderLine>list(
            "tenantId = ?1 and orderId in ?2", Sort.by("orderId").and("id"), tenant, ids))
      items
          .computeIfAbsent(l.orderId, k -> new ArrayList<>())
          .add(new Views.OrderItem(l.productName, l.unit, Quantity.wire(l.quantity)));
    Map<Long, String> names = resellerNames(tenant, found.stream().map(o -> o.resellerId).toList());
    return found.stream()
        .map(
            o ->
                new Views.OrderSummary(
                    o.id,
                    o.number,
                    o.resellerId,
                    names.get(o.resellerId),
                    Times.instant(o.placedAt),
                    o.deliveryDate.toString(),
                    o.status,
                    o.total,
                    o.paid,
                    o.note,
                    items.getOrDefault(o.id, List.of())))
        .toList();
  }

  static Map<Long, String> resellerNames(long tenant, Collection<Long> ids) {
    Map<Long, String> names = new HashMap<>();
    for (Reseller r :
        Reseller.<Reseller>list("tenantId = ?1 and id in ?2", tenant, new HashSet<>(ids)))
      names.put(r.id, r.name);
    return names;
  }

  private static OrderRules.Status statusValue(String value) {
    try {
      return OrderRules.Status.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw Problem.field("status", "Use placed, confirmed, delivered or cancelled");
    }
  }

  @Transactional
  public Views.ProductionRun production(String subject, String date) {
    LocalDate delivery = date(date);
    long tenant = Owners.tenant(subject).id;
    var lines =
        OrderLine.getEntityManager()
            .createQuery(
                "select l.productId, p.name, l.unit, sum(l.quantity), count(distinct o.id)"
                    + " from OrderLine l"
                    + " join SalesOrder o on o.id = l.orderId and o.tenantId = l.tenantId"
                    + " join Product p on p.tenantId = l.tenantId and p.id = l.productId"
                    + " where o.tenantId = ?1 and o.deliveryDate = ?2 and o.status = 'confirmed'"
                    + " group by l.productId, p.name, l.unit order by l.productId",
                Object[].class)
            .setParameter(1, tenant)
            .setParameter(2, delivery)
            .getResultList()
            .stream()
            .map(
                r ->
                    new Views.ProductionLine(
                        (Long) r[0],
                        (String) r[1],
                        (String) r[2],
                        Quantity.wire((BigDecimal) r[3]),
                        ((Number) r[4]).longValue()))
            .toList();
    long unconfirmed =
        SalesOrder.count(
            "tenantId = ?1 and deliveryDate = ?2 and status = 'placed'", tenant, delivery);
    return new Views.ProductionRun(date, lines, unconfirmed);
  }

  static SalesOrder order(long tenant, long id, Long reseller, LockModeType lock) {
    return SalesOrder.inTenant(tenant, id, reseller, lock)
        .orElseThrow(() -> new Problem(404, "Not found"));
  }

  private static List<OrderLine> lines(SalesOrder o) {
    return OrderLine.list("tenantId = ?1 and orderId = ?2", Sort.by("id"), o.tenantId, o.id);
  }

  /** An order as the owner sees it, with its history. */
  Views.Order ownerView(SalesOrder o) {
    return view(o, lines(o), history.forOwner(o));
  }

  /** An order as its reseller sees it: the history names the business, never the owner. */
  Views.Order resellerView(SalesOrder o) {
    return view(o, lines(o), history.forReseller(o));
  }

  static Views.Order view(SalesOrder o, List<OrderLine> lines) {
    return view(o, lines, List.of());
  }

  private static Views.Order view(
      SalesOrder o, List<OrderLine> lines, List<Views.OrderEvent> events) {
    return new Views.Order(
        o.id,
        o.resellerId,
        resellerNames(o.tenantId, List.of(o.resellerId)).get(o.resellerId),
        o.number,
        Times.instant(o.placedAt),
        o.deliveryDate.toString(),
        o.status,
        o.total,
        o.note,
        o.paid,
        Times.instant(o.confirmedAt),
        Times.instant(o.deliveredAt),
        Times.instant(o.cancelledAt),
        Times.instant(o.paidAt),
        lines.stream()
            .map(
                l ->
                    new Views.OrderLine(
                        l.productId,
                        l.productName,
                        l.unit,
                        Quantity.wire(l.quantity),
                        l.price,
                        l.total))
            .toList(),
        events);
  }
}
