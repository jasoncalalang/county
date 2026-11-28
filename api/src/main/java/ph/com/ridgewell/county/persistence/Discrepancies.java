package ph.com.ridgewell.county.persistence;

import static jakarta.persistence.LockModeType.NONE;
import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;
import static ph.com.ridgewell.county.domain.OrderRules.Actor.OWNER;
import static ph.com.ridgewell.county.domain.OrderRules.Actor.RESELLER;
import static ph.com.ridgewell.county.domain.DiscrepancyRules.Status.APPROVED;
import static ph.com.ridgewell.county.domain.DiscrepancyRules.Status.DECLINED;
import static ph.com.ridgewell.county.domain.OrderRules.Status.DELIVERED;

import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ph.com.ridgewell.county.api.Inputs;
import ph.com.ridgewell.county.api.Problem;
import ph.com.ridgewell.county.api.Views;
import ph.com.ridgewell.county.domain.DiscrepancyRules;
import ph.com.ridgewell.county.domain.DiscrepancyRules.Status;
import ph.com.ridgewell.county.domain.OrderRules.Actor;
import ph.com.ridgewell.county.domain.Quantity;
import ph.com.ridgewell.county.domain.Unit;

/**
 * Short-delivery reports (ADR 0012). A report is a separate record against a delivered order: it
 * copies what the order line says and never changes the order.
 *
 * <p>Locks follow ADR 0013. A reseller's action locks the reseller, then the order; an owner's
 * locks the order. Every write happens under the order's write lock, so the report is never
 * locked on its own and is not a lock target (ADR 0015); the idempotency key is checked under it.
 * Products are not locked: lines copy from the order's own lines.
 */
@ApplicationScoped
public class Discrepancies {
  @Inject Clock clock;
  @Inject ResellerLinks links;

  public record Filing(boolean replay, Views.Discrepancy report) {}

  /** The owner's order, write-locked. */
  private static SalesOrder ownersOrder(String subject, long orderId) {
    return Orders.order(Owners.tenant(subject).id, orderId, null, PESSIMISTIC_WRITE);
  }

  /** The link's reseller, write-locked, then the reseller's order, write-locked (ADR 0013). */
  private SalesOrder resellersOrder(String token, long orderId) {
    var reseller = links.resolve(token, PESSIMISTIC_WRITE);
    return Orders.order(reseller.tenantId, orderId, reseller.id, PESSIMISTIC_WRITE);
  }

  @Transactional
  public Filing fileByOwner(String subject, long orderId, Inputs.Discrepancy input) {
    var order = ownersOrder(subject, orderId);
    return file(order, input, OWNER);
  }

  @Transactional
  public Filing fileByReseller(String token, long orderId, Inputs.Discrepancy input) {
    var order = resellersOrder(token, orderId);
    return file(order, input, RESELLER);
  }

  @Transactional
  public Views.Discrepancy forOwner(String subject, long orderId) {
    var order = Orders.order(Owners.tenant(subject).id, orderId, null, NONE);
    return existing(order);
  }

  @Transactional
  public Views.Discrepancy forReseller(String token, long orderId) {
    var reseller = links.resolve(token, NONE);
    var order = Orders.order(reseller.tenantId, orderId, reseller.id, NONE);
    return existing(order);
  }

  @Transactional
  public Views.Discrepancy approveByOwner(String subject, long orderId, Inputs.Resolve input) {
    var order = ownersOrder(subject, orderId);
    return resolve(order, input, OWNER, APPROVED);
  }

  @Transactional
  public Views.Discrepancy declineByOwner(String subject, long orderId, Inputs.Resolve input) {
    var order = ownersOrder(subject, orderId);
    return resolve(order, input, OWNER, DECLINED);
  }

  @Transactional
  public Views.Discrepancy approveByReseller(String token, long orderId, Inputs.Resolve input) {
    var order = resellersOrder(token, orderId);
    return resolve(order, input, RESELLER, APPROVED);
  }

  @Transactional
  public Views.Discrepancy declineByReseller(String token, long orderId, Inputs.Resolve input) {
    var order = resellersOrder(token, orderId);
    return resolve(order, input, RESELLER, DECLINED);
  }

  private static final String SUMMARIES =
      "select r, o from DiscrepancyReport r join SalesOrder o"
          + " on o.tenantId = r.tenantId and o.id = r.orderId where r.tenantId = ?1";
  private static final String NEWEST_FIRST = " order by r.filedAt desc, r.id desc";

  /** The owner's reports, newest first. */
  @Transactional
  public List<Views.DiscrepancySummary> list(String subject) {
    long tenant = Owners.tenant(subject).id;
    var found =
        DiscrepancyReport.getEntityManager()
            .createQuery(SUMMARIES + NEWEST_FIRST, Object[].class)
            .setParameter(1, tenant);
    return summaries(tenant, found.getResultList());
  }

  /** The owner's reports in one status, newest first. */
  @Transactional
  public List<Views.DiscrepancySummary> listByStatus(String subject, Status status) {
    long tenant = Owners.tenant(subject).id;
    var found =
        DiscrepancyReport.getEntityManager()
            .createQuery(SUMMARIES + " and r.status = ?2" + NEWEST_FIRST, Object[].class)
            .setParameter(1, tenant)
            .setParameter(2, status.wire());
    return summaries(tenant, found.getResultList());
  }

  private static List<Views.DiscrepancySummary> summaries(long tenant, List<Object[]> rows) {
    Map<Long, String> names =
        Orders.resellerNames(
            tenant, rows.stream().map(row -> ((SalesOrder) row[1]).resellerId).toList());
    return rows.stream()
        .map(
            row -> {
              var r = (DiscrepancyReport) row[0];
              var o = (SalesOrder) row[1];
              return new Views.DiscrepancySummary(
                  r.id,
                  r.orderId,
                  o.number,
                  o.resellerId,
                  names.get(o.resellerId),
                  r.filedBy,
                  r.status,
                  r.shortfallTotal,
                  Times.instant(r.filedAt));
            })
        .toList();
  }

  /** Resolves the report of a locked order, if the rule lets this party do it. */
  private Views.Discrepancy resolve(
      SalesOrder order, Inputs.Resolve input, Actor resolver, Status target) {
    var report =
        DiscrepancyReport.forOrder(order.tenantId, order.id)
            .orElseThrow(() -> new Problem(404, "Not found"));
    try {
      DiscrepancyRules.resolve(report.currentStatus(), report.filer(), resolver, target);
    } catch (IllegalArgumentException e) {
      throw new Problem(409, e.getMessage());
    }
    report.status = target.wire();
    report.resolvedBy = Stored.of(resolver);
    report.resolvedAt = Times.now(clock);
    report.resolutionNote = input == null || input.note() == null ? "" : input.note();
    DiscrepancyReport.flush();
    return view(report, order.number, lines(report));
  }

  private Views.Discrepancy existing(SalesOrder order) {
    var report =
        DiscrepancyReport.forOrder(order.tenantId, order.id)
            .orElseThrow(() -> new Problem(404, "Not found"));
    return view(report, order.number, lines(report));
  }

  /** Files a report on a locked order, or replays the one this filer already filed with the key. */
  private Filing file(SalesOrder order, Inputs.Discrepancy input, Actor filer) {
    var keyed = DiscrepancyReport.byKey(order.tenantId, input.idempotencyKey());
    if (keyed.isPresent()) {
      var known = keyed.get();
      // A key spent on another order or by the other party must not reveal that report.
      if (known.orderId != order.id || !known.filedBy.equals(Stored.of(filer)))
        throw new Problem(409, "Idempotency key already used");
      return new Filing(true, view(known, order.number, lines(known)));
    }
    var status = order.currentStatus();
    if (status != DELIVERED) throw new Problem(409, "Order is now " + Stored.of(status));
    if (DiscrepancyReport.forOrder(order.tenantId, order.id).isPresent())
      throw new Problem(409, "This order already has a report");

    var captured = capture(order, input.lines());
    var report = new DiscrepancyReport();
    report.tenantId = order.tenantId;
    report.orderId = order.id;
    report.filedBy = Stored.of(filer);
    report.filedAt = Times.now(clock);
    report.note = input.note() == null ? "" : input.note();
    report.shortfallTotal = total(captured);
    report.idempotencyKey = input.idempotencyKey();
    report.persist();
    for (var line : captured) {
      line.reportId = report.id;
      line.persist();
    }
    DiscrepancyReport.flush();
    return new Filing(false, view(report, order.number, captured));
  }

  /** The submitted lines as stored lines, copied from the order's lines. A field error names the line. */
  private static List<DiscrepancyLine> capture(SalesOrder order, List<Inputs.DiscrepancyLine> submitted) {
    Map<Long, OrderLine> ordered = new LinkedHashMap<>();
    for (var line :
        OrderLine.<OrderLine>list(
            "tenantId = ?1 and orderId = ?2", Sort.by("id"), order.tenantId, order.id))
      ordered.put(line.productId, line);
    Set<Long> seen = new HashSet<>();
    List<DiscrepancyLine> captured = new ArrayList<>();
    for (int i = 0; i < submitted.size(); i++) {
      String field = "lines[" + i + "]";
      var line = submitted.get(i);
      if (!seen.add(line.productId()))
        throw Problem.field(field + ".productId", "This product is already on another line");
      var from = ordered.get(line.productId());
      if (from == null) throw Problem.field(field + ".productId", "This product is not on the order");
      if (!Unit.of(from.unit).allows(line.receivedQuantity()))
        throw Problem.field(
            field + ".receivedQuantity",
            from.productName + " is sold by the " + from.unit + ", so enter a whole number");
      var saved = new DiscrepancyLine();
      saved.tenantId = order.tenantId;
      saved.productId = from.productId;
      saved.productName = from.productName;
      saved.unit = from.unit;
      saved.orderedQuantity = from.quantity;
      saved.receivedQuantity = line.receivedQuantity();
      saved.price = from.price;
      try {
        saved.shortfallTotal =
            DiscrepancyRules.shortfall(from.quantity, line.receivedQuantity(), from.price);
      } catch (IllegalArgumentException e) {
        throw Problem.field(field + ".receivedQuantity", e.getMessage());
      }
      captured.add(saved);
    }
    return captured;
  }

  private static BigDecimal total(List<DiscrepancyLine> lines) {
    try {
      return DiscrepancyRules.total(lines.stream().map(l -> l.shortfallTotal).toList());
    } catch (IllegalArgumentException e) {
      throw Problem.field("lines", e.getMessage());
    }
  }

  private static List<DiscrepancyLine> lines(DiscrepancyReport report) {
    return DiscrepancyLine.list(
        "tenantId = ?1 and reportId = ?2", Sort.by("id"), report.tenantId, report.id);
  }

  private static Views.Discrepancy view(
      DiscrepancyReport r, long orderNumber, List<DiscrepancyLine> lines) {
    return new Views.Discrepancy(
        r.id,
        r.orderId,
        orderNumber,
        r.filedBy,
        Times.instant(r.filedAt),
        r.status,
        r.note,
        r.resolutionNote,
        r.resolvedBy,
        Times.instant(r.resolvedAt),
        r.shortfallTotal,
        lines.stream()
            .map(
                l ->
                    new Views.DiscrepancyLine(
                        l.productId,
                        l.productName,
                        l.unit,
                        Quantity.wire(l.orderedQuantity),
                        Quantity.wire(l.receivedQuantity),
                        l.price,
                        l.shortfallTotal))
            .toList());
  }
}
