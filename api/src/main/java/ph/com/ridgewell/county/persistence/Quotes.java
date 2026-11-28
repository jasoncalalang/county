package ph.com.ridgewell.county.persistence;

import static jakarta.persistence.LockModeType.NONE;
import static jakarta.persistence.LockModeType.PESSIMISTIC_READ;
import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;

import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import ph.com.ridgewell.county.api.*;
import ph.com.ridgewell.county.domain.Quantity;
import ph.com.ridgewell.county.domain.QuoteRules;
import ph.com.ridgewell.county.domain.QuoteRules.Actor;
import ph.com.ridgewell.county.domain.QuoteRules.Status;
import ph.com.ridgewell.county.domain.ResellerStatus;

/**
 * Quotes (build plan §3). The owner creates a quote for one reseller and sends it; the reseller
 * sees it on their order link and accepts it, which places an order at the quoted prices, or
 * declines it.
 *
 * <p>Locks follow ADR 0013 and ADR 0015. Create: reseller (share), business, products by
 * ascending id; the quote it inserts is not a lock target. Accept: reseller, business, then the
 * quote; the order it inserts is not a lock target, and it reads no product, because the quote's
 * captured prices are the order's prices. Send, cancel and decline lock only the quote.
 */
@ApplicationScoped
public class Quotes {
  @Inject Orders orders;
  @Inject Pricing pricing;
  @Inject ResellerLinks links;

  public record Acceptance(boolean replay, Views.Order order) {}

  private static LocalDate today() {
    return LocalDate.now(Orders.ZONE);
  }

  /** The status a reader sees, with expiry applied. */
  static Status status(Quote q) {
    return QuoteRules.effective(
        Status.valueOf(q.status.toUpperCase(Locale.ROOT)), q.expiresOn, today());
  }

  private static Quote quote(long tenant, long id, Long reseller, LockModeType lock) {
    return Quote.inTenant(tenant, id, reseller, lock)
        .orElseThrow(() -> new Problem(404, "Not found"));
  }

  /** A quote on a reseller's link: theirs, and sent at some point. Drafts are not visible. */
  private static Quote visible(Reseller reseller, long id, LockModeType lock) {
    var q = quote(reseller.tenantId, id, reseller.id, lock);
    if (q.sentAt == null) throw new Problem(404, "Not found");
    return q;
  }

  private static void move(Quote q, Status target, Actor actor) {
    Status current = status(q);
    try {
      QuoteRules.transition(current, target, actor);
    } catch (IllegalArgumentException e) {
      throw new Problem(409, "Quote is now " + current.wire());
    }
  }

  @Transactional
  public Views.Quote create(String subject, Inputs.Quote input) {
    long tenant = Owners.tenant(subject).id;
    // The share lock keeps the reseller's tier fixed while the lines are priced.
    var reseller =
        Reseller.inTenant(tenant, input.resellerId(), PESSIMISTIC_READ)
            .orElseThrow(() -> new Problem(404, "Not found"));
    if (reseller.status.equals(ResellerStatus.BLOCKED.wire()))
      throw Problem.field("resellerId", "This reseller is blocked");
    if (input.expiresOn().isBefore(today()))
      throw Problem.field("expiresOn", "Choose today or a later day");
    // The business lock allocates the quote number without gaps.
    Business business = Business.findById(tenant, PESSIMISTIC_WRITE);
    var priced = pricing.price(reseller, input.lines());
    var q = new Quote();
    q.tenantId = tenant;
    q.resellerId = reseller.id;
    q.number = business.nextQuoteNumber++;
    q.createdAt = Times.now();
    q.expiresOn = input.expiresOn();
    q.deliveryDate = Orders.delivery(business);
    q.total = priced.total();
    q.note = input.note() == null ? "" : input.note();
    q.persist();
    List<QuoteLine> lines = new ArrayList<>();
    for (var p : priced.lines()) {
      var line = new QuoteLine();
      line.tenantId = tenant;
      line.quoteId = q.id;
      line.productId = p.product().id;
      line.productName = p.product().name;
      line.unit = p.product().unit;
      line.quantity = p.quantity();
      line.price = p.price();
      line.total = p.total();
      line.persist();
      lines.add(line);
    }
    Quote.flush();
    return view(q, lines);
  }

  @Transactional
  public Views.Quote get(String subject, long id) {
    return view(quote(Owners.tenant(subject).id, id, null, NONE));
  }

  @Transactional
  public List<Views.QuoteSummary> list(String subject, String status, String resellerId) {
    long tenant = Owners.tenant(subject).id;
    StringBuilder query = new StringBuilder("tenantId = :tenant");
    Map<String, Object> params = new HashMap<>();
    params.put("tenant", tenant);
    if (resellerId != null) {
      long reseller;
      try {
        reseller = Long.parseLong(resellerId);
      } catch (NumberFormatException e) {
        throw Problem.field("resellerId", "Enter a reseller id");
      }
      query.append(" and resellerId = :reseller");
      params.put("reseller", reseller);
    }
    if (status != null) {
      Status wanted = statusValue(status);
      // Expiry is read, not stored: a sent quote past its last day is expired.
      switch (wanted) {
        case SENT -> query.append(" and status = 'sent' and expiresOn >= :today");
        case EXPIRED ->
            query.append(" and (status = 'expired' or status = 'sent' and expiresOn < :today)");
        default -> {
          query.append(" and status = :status");
          params.put("status", wanted.wire());
        }
      }
      if (wanted == Status.SENT || wanted == Status.EXPIRED) params.put("today", today());
    }
    List<Quote> found =
        Quote.<Quote>list(query.toString(), Sort.descending("number"), params);
    Map<Long, String> names =
        Orders.resellerNames(tenant, found.stream().map(q -> q.resellerId).toList());
    return found.stream().map(q -> summary(q, names.get(q.resellerId))).toList();
  }

  private static Status statusValue(String value) {
    try {
      return Status.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw Problem.field("status", "Use draft, sent, accepted, declined, expired or cancelled");
    }
  }

  @Transactional
  public Views.Quote send(String subject, long id) {
    var q = quote(Owners.tenant(subject).id, id, null, PESSIMISTIC_WRITE);
    move(q, Status.SENT, Actor.OWNER);
    // A draft past its last day would arrive already expired.
    if (q.expiresOn.isBefore(today()))
      throw new Problem(409, "Quote expired on " + q.expiresOn + "; create a new one");
    q.status = Status.SENT.wire();
    q.sentAt = Times.now();
    Quote.flush();
    return view(q);
  }

  @Transactional
  public Views.Quote cancel(String subject, long id) {
    var q = quote(Owners.tenant(subject).id, id, null, PESSIMISTIC_WRITE);
    move(q, Status.CANCELLED, Actor.OWNER);
    q.status = Status.CANCELLED.wire();
    q.cancelledAt = Times.now();
    Quote.flush();
    return view(q);
  }

  /** Sent quotes the reseller can still accept, newest first. */
  @Transactional
  public List<Views.QuoteSummary> open(String token) {
    var reseller = links.resolve(token, NONE);
    return Quote.<Quote>list(
            "tenantId = ?1 and resellerId = ?2 and status = 'sent' and expiresOn >= ?3",
            Sort.descending("number"),
            reseller.tenantId,
            reseller.id,
            today())
        .stream()
        .map(q -> summary(q, reseller.name))
        .toList();
  }

  @Transactional
  public Views.Quote getPublic(String token, long id) {
    var reseller = links.resolve(token, NONE);
    return view(visible(reseller, id, NONE));
  }

  @Transactional
  public Views.Quote decline(String token, long id) {
    var reseller = links.resolve(token, NONE);
    var q = visible(reseller, id, PESSIMISTIC_WRITE);
    move(q, Status.DECLINED, Actor.RESELLER);
    q.status = Status.DECLINED.wire();
    q.declinedAt = Times.now();
    Quote.flush();
    return view(q);
  }

  /**
   * Places an order from a sent quote at the quote's captured prices. Unlike placing an order, it
   * does not read today's prices and does not refuse a product deactivated since the quote was
   * created: the quote is the offer the reseller accepted. Accepting again, with any key, returns
   * the order the quote produced.
   */
  @Transactional
  public Acceptance accept(String token, long id, Inputs.AcceptQuote input) {
    // Accepting marks the reseller as having ordered, as placing does: hence the write lock.
    var reseller = links.resolve(token, PESSIMISTIC_WRITE);
    // Serializes order numbering and the idempotency check for this business.
    Business business = Business.findById(reseller.tenantId, PESSIMISTIC_WRITE);
    var q = visible(reseller, id, PESSIMISTIC_WRITE);
    var keyed =
        SalesOrder.<SalesOrder>find(
                "tenantId = ?1 and idempotencyKey = ?2",
                reseller.tenantId,
                input.idempotencyKey())
            .singleResultOptional();
    // A key already spent on another order must not reveal or replay that order here.
    if (keyed.isPresent() && !keyed.get().id.equals(q.acceptedOrderId))
      throw new Problem(409, "Idempotency key already used");
    if (q.acceptedOrderId != null)
      return new Acceptance(
          true, orders.resellerView(SalesOrder.<SalesOrder>findById(q.acceptedOrderId)));
    move(q, Status.ACCEPTED, Actor.RESELLER);
    List<QuoteLine> quoted =
        QuoteLine.list("tenantId = ?1 and quoteId = ?2", Sort.by("id"), q.tenantId, q.id);
    Instant now = Times.now();
    var order = new SalesOrder();
    order.tenantId = reseller.tenantId;
    order.resellerId = reseller.id;
    order.number = business.nextOrderNumber++;
    order.placedAt = now;
    order.deliveryDate = acceptedDeliveryDate(q, business);
    order.total = q.total;
    order.note = "";
    order.idempotencyKey = input.idempotencyKey();
    order.persist();
    List<OrderLine> lines = new ArrayList<>();
    for (var l : quoted) {
      var line = new OrderLine();
      line.tenantId = l.tenantId;
      line.orderId = order.id;
      line.productId = l.productId;
      line.productName = l.productName;
      line.unit = l.unit;
      line.quantity = l.quantity;
      line.price = l.price;
      line.total = l.total;
      line.persist();
      lines.add(line);
    }
    q.status = Status.ACCEPTED.wire();
    q.acceptedAt = now;
    q.acceptedOrderId = order.id;
    reseller.hasOrdered = true;
    reseller.status = ResellerStatus.ACTIVE.wire();
    SalesOrder.flush();
    return new Acceptance(false, Orders.view(order, lines));
  }

  /**
   * The accepted order's delivery date: computed at acceptance, as for any order placed now, so a
   * quote accepted after the cut-off never promises a date the kitchen can no longer meet. The
   * quote's own delivery date stays as the estimate it showed (build plan §3.6).
   */
  private static LocalDate acceptedDeliveryDate(Quote q, Business business) {
    return Orders.delivery(business);
  }

  private static Views.QuoteSummary summary(Quote q, String resellerName) {
    return new Views.QuoteSummary(
        q.id,
        q.number,
        q.resellerId,
        resellerName,
        Times.instant(q.createdAt),
        q.deliveryDate.toString(),
        q.expiresOn.toString(),
        status(q).wire(),
        q.total);
  }

  private static Views.Quote view(Quote q) {
    return view(
        q, QuoteLine.list("tenantId = ?1 and quoteId = ?2", Sort.by("id"), q.tenantId, q.id));
  }

  private static Views.Quote view(Quote q, List<QuoteLine> lines) {
    return new Views.Quote(
        q.id,
        q.resellerId,
        Orders.resellerNames(q.tenantId, List.of(q.resellerId)).get(q.resellerId),
        q.number,
        Times.instant(q.createdAt),
        q.deliveryDate.toString(),
        q.expiresOn.toString(),
        status(q).wire(),
        q.total,
        q.note,
        q.acceptedOrderId,
        Times.instant(q.sentAt),
        Times.instant(q.acceptedAt),
        Times.instant(q.declinedAt),
        Times.instant(q.cancelledAt),
        lines.stream()
            .map(
                l ->
                    new Views.QuoteLine(
                        l.productId,
                        l.productName,
                        l.unit,
                        Quantity.wire(l.quantity),
                        l.price,
                        l.total))
            .toList());
  }
}
