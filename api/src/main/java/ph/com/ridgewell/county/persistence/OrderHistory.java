package ph.com.ridgewell.county.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
    event.fromStatus = stored(from);
    event.toStatus = stored(to);
    event.paid = order.paid;
    event.actorType = stored(actor);
    return event;
  }

  private static String stored(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
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
        .map(
            r ->
                new Views.OrderEvent(
                    Times.instant((Instant) r[0]),
                    (String) r[1],
                    (String) r[2],
                    (Boolean) r[3],
                    (String) r[4],
                    (String) r[5]))
        .toList();
  }
}
