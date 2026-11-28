package ph.com.ridgewell.county.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import ph.com.ridgewell.county.api.Views;
import ph.com.ridgewell.county.domain.OrderRules;

/**
 * The history of an order. Events are written only by an order action, after its rule check has
 * passed and in its transaction, and are never locked: they are not a lock target (ADR 0015).
 */
@ApplicationScoped
public class OrderHistory {
  public void byOwner(
      SalesOrder order,
      OrderRules.Status from,
      OrderRules.Status to,
      long ownerUserId,
      Instant at) {
    var event = event(order, from, to, OrderRules.Actor.OWNER, at);
    event.actorUserId = ownerUserId;
    event.persist();
  }

  public void byReseller(
      SalesOrder order,
      OrderRules.Status from,
      OrderRules.Status to,
      long resellerId,
      Instant at) {
    var event = event(order, from, to, OrderRules.Actor.RESELLER, at);
    event.actorResellerId = resellerId;
    event.persist();
  }

  private static OrderEvent event(
      SalesOrder order,
      OrderRules.Status from,
      OrderRules.Status to,
      OrderRules.Actor actor,
      Instant at) {
    var event = new OrderEvent();
    event.tenantId = order.tenantId;
    event.orderId = order.id;
    event.occurredAt = at;
    event.fromStatus = Stored.of(from);
    event.toStatus = Stored.of(to);
    event.paid = order.paid;
    event.actorType = Stored.of(actor);
    return event;
  }

  private static Views.OrderEvent view(Object[] row) {
    return new Views.OrderEvent(
        Times.instant((Instant) row[0]),
        (String) row[1],
        (String) row[2],
        (Boolean) row[3],
        (String) row[4],
        (String) row[5]);
  }

  /** The order's events, oldest first, as the owner sees them: with the actor's name. */
  public List<Views.OrderEvent> forOwner(SalesOrder order) {
    return OrderEvent.getEntityManager()
        .createQuery(
            "select e.occurredAt, e.fromStatus, e.toStatus, e.paid, e.actorType,"
                + " coalesce(u.displayName, r.name) from OrderEvent e"
                + " left join AppUser u on u.id = e.actorUserId"
                + " left join Reseller r on r.tenantId = e.tenantId and r.id = e.actorResellerId"
                + " where e.tenantId = ?1 and e.orderId = ?2 order by e.occurredAt, e.id",
            Object[].class)
        .setParameter(1, order.tenantId)
        .setParameter(2, order.id)
        .getResultList()
        .stream()
        .map(OrderHistory::view)
        .toList();
  }

  /**
   * The order's events, oldest first, as its reseller sees them: the reseller's own name, and the
   * business name for what the owner did. No owner name, email or id.
   */
  public List<Views.OrderEvent> forReseller(SalesOrder order) {
    String business = Business.<Business>findById(order.tenantId).name;
    return OrderEvent.getEntityManager()
        .createQuery(
            "select e.occurredAt, e.fromStatus, e.toStatus, e.paid, e.actorType,"
                + " coalesce(r.name, ?3) from OrderEvent e"
                + " left join Reseller r on r.tenantId = e.tenantId and r.id = e.actorResellerId"
                + " where e.tenantId = ?1 and e.orderId = ?2 order by e.occurredAt, e.id",
            Object[].class)
        .setParameter(1, order.tenantId)
        .setParameter(2, order.id)
        .setParameter(3, business)
        .getResultList()
        .stream()
        .map(OrderHistory::view)
        .toList();
  }
}
