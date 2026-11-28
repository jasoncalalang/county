package ph.com.ridgewell.county.persistence;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;
import static ph.com.ridgewell.county.domain.OrderRules.Actor.OWNER;
import static ph.com.ridgewell.county.domain.OrderRules.Actor.RESELLER;
import static ph.com.ridgewell.county.domain.OrderRules.Status.CANCELLED;
import static ph.com.ridgewell.county.domain.OrderRules.Status.CONFIRMED;
import static ph.com.ridgewell.county.domain.OrderRules.Status.DELIVERED;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import ph.com.ridgewell.county.api.Problem;
import ph.com.ridgewell.county.api.Views;
import ph.com.ridgewell.county.domain.OrderRules;

/**
 * What an owner or a reseller does to an order. One method per action, each one transaction that
 * locks the order, checks the rule, changes the order and records the event. Lock order is
 * unchanged (ADR 0013): the reseller for a reseller's cancel, then the order. The owner's user id
 * is read without a lock.
 */
@ApplicationScoped
public class OrderActions {
  @Inject Clock clock;
  @Inject OrderHistory history;
  @Inject Orders orders;

  @Transactional
  public Views.Order confirm(String subject, long orderId) {
    var owner = Owners.tenant(subject);
    var order = Orders.order(owner.id, orderId, null, PESSIMISTIC_WRITE);
    Instant at = Times.now(clock);
    var from = move(order, CONFIRMED, OWNER);
    order.confirmedAt = at;
    return recordedByOwner(owner, order, from, CONFIRMED, at);
  }

  @Transactional
  public Views.Order cancelByOwner(String subject, long orderId) {
    var owner = Owners.tenant(subject);
    var order = Orders.order(owner.id, orderId, null, PESSIMISTIC_WRITE);
    Instant at = Times.now(clock);
    var from = move(order, CANCELLED, OWNER);
    order.cancelledAt = at;
    return recordedByOwner(owner, order, from, CANCELLED, at);
  }

  @Transactional
  public Views.Order cancelByReseller(String token, long orderId) {
    var reseller = Orders.reseller(token, PESSIMISTIC_WRITE);
    var order = Orders.order(reseller.tenantId, orderId, reseller.id, PESSIMISTIC_WRITE);
    Instant at = Times.now(clock);
    var from = move(order, CANCELLED, RESELLER);
    order.cancelledAt = at;
    history.byReseller(order, from, CANCELLED, reseller.id, at);
    SalesOrder.flush();
    return Orders.view(order);
  }

  @Transactional
  public Views.Order deliver(String subject, long orderId, boolean paid) {
    var owner = Owners.tenant(subject);
    var order = Orders.order(owner.id, orderId, null, PESSIMISTIC_WRITE);
    Instant at = Times.now(clock);
    var from = move(order, DELIVERED, OWNER);
    order.deliveredAt = at;
    order.paid = paid;
    order.paidAt = paid ? at : null;
    return recordedByOwner(owner, order, from, DELIVERED, at);
  }

  @Transactional
  public Views.Order markPaid(String subject, long orderId) {
    var owner = Owners.tenant(subject);
    var order = Orders.order(owner.id, orderId, null, PESSIMISTIC_WRITE);
    Instant at = Times.now(clock);
    var status = order.currentStatus();
    if (status != DELIVERED || order.paid) throw changed(status);
    order.paid = true;
    order.paidAt = at;
    return recordedByOwner(owner, order, DELIVERED, DELIVERED, at);
  }

  /** Applies the status rule and returns the status the order had. */
  private static OrderRules.Status move(
      SalesOrder order, OrderRules.Status target, OrderRules.Actor actor) {
    var from = order.currentStatus();
    try {
      OrderRules.transition(from, target, actor);
    } catch (IllegalArgumentException e) {
      throw changed(from);
    }
    order.moveTo(target);
    return from;
  }

  private static Problem changed(OrderRules.Status status) {
    return new Problem(409, "Order is now " + Stored.of(status));
  }

  private Views.Order recordedByOwner(
      Business owner,
      SalesOrder order,
      OrderRules.Status from,
      OrderRules.Status to,
      Instant at) {
    history.byOwner(order, from, to, owner.ownerUserId, at);
    SalesOrder.flush();
    return orders.ownerView(order);
  }
}
