// Pure helpers for the dashboard's order screens, kept apart so they can be tested.
import type { OrderEventView, OrderItemView, OrderSummaryView, OrderView } from "../api-types";
import { formatMoney, manilaDate, type Status } from "../format";

const RANK: Record<Status, number> = { placed: 0, confirmed: 1, delivered: 2, cancelled: 3 };

/** Placed first, then confirmed, delivered, cancelled; newest first within a status (UI 6.3). */
export function sortOrders<T extends Pick<OrderSummaryView, "status" | "placedAt">>(orders: T[]): T[] {
  return [...orders].sort((a, b) => RANK[a.status] - RANK[b.status] || b.placedAt.localeCompare(a.placedAt));
}

export function countByStatus(orders: Pick<OrderSummaryView, "status">[]): Record<Status | "all", number> {
  const counts = { all: orders.length, placed: 0, confirmed: 0, delivered: 0, cancelled: 0 };
  for (const o of orders) counts[o.status] += 1;
  return counts;
}

/** "3 orders · ₱5,280.00" and, apart, "1 needs confirmation". The total leaves out cancelled. */
export function summarize(orders: Pick<OrderSummaryView, "status" | "total">[]): { main: string; warning: string | null } {
  const total = orders.filter((o) => o.status !== "cancelled").reduce((sum, o) => sum + Math.round(o.total * 100), 0);
  const placed = orders.filter((o) => o.status === "placed").length;
  return {
    main: `${orders.length} ${orders.length === 1 ? "order" : "orders"} · ${formatMoney(total / 100)}`,
    warning: placed ? `${placed} ${placed === 1 ? "needs" : "need"} confirmation` : null,
  };
}

/** "10 pack Longganisa Hamonado, 5 kg Tocino", one line per row (UI design 6.3). */
export function itemsSummary(items: OrderItemView[] | undefined): string {
  return (items ?? []).map((i) => `${i.quantity} ${i.unit} ${i.productName}`).join(", ");
}

function addDays(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** ISO weekday, 1 = Monday to 7 = Sunday, as the API stores delivery days. */
function isoWeekday(iso: string): number {
  const day = new Date(`${iso}T00:00:00Z`).getUTCDay();
  return day === 0 ? 7 : day;
}

/** The previous or next configured delivery day from `iso`, for the date picker's arrows. */
export function stepDeliveryDay(iso: string, deliveryDays: number[], direction: 1 | -1): string {
  const days = deliveryDays.length ? deliveryDays : [1, 2, 3, 4, 5, 6, 7];
  let date = iso;
  for (let i = 0; i < 7; i += 1) {
    date = addDays(date, direction);
    if (days.includes(isoWeekday(date))) return date;
  }
  return addDays(iso, direction);
}

/**
 * The date the Orders and Production run pages open on: the earliest delivery date with an
 * order not yet delivered or cancelled, else the next delivery date, which the API's settings
 * preview gives. `next` is only absent when that call failed; then the next configured
 * delivery day after today stands in.
 */
export function defaultDeliveryDate(
  open: Pick<OrderSummaryView, "deliveryDate">[],
  deliveryDays: number[],
  next?: string,
  now: Date = new Date(),
): string {
  const earliest = open.map((o) => o.deliveryDate).sort()[0];
  return earliest ?? next ?? stepDeliveryDay(manilaDate(now), deliveryDays, 1);
}

/** The settings preview's query string: `cutoff=16:00&deliveryDays=1&deliveryDays=4`. */
export function previewQuery(cutoff: string, deliveryDays: number[]): string {
  const query = new URLSearchParams({ cutoff: cutoff.slice(0, 5) });
  for (const day of deliveryDays) query.append("deliveryDays", String(day));
  return query.toString();
}

export interface HistoryEntry {
  label: string;
  at: string;
  /** Who did it, when the API says. */
  by: string | null;
}

export type HistorySource = Pick<
  OrderView,
  "placedAt" | "resellerName" | "confirmedAt" | "deliveredAt" | "cancelledAt" | "paidAt" | "events"
>;

function eventLabel(event: OrderEventView): string {
  if (event.toStatus === "cancelled") return "Cancelled";
  if (event.toStatus === "confirmed") return "Confirmed";
  if (event.fromStatus === "delivered") return "Marked paid";
  return event.paid ? "Delivered and paid" : "Delivered";
}

/**
 * The order's history for its detail page, oldest first. The placement comes from `placedAt`,
 * by the reseller; the rest from the API's events. An order changed before events began has
 * none, so it falls back to its timestamps, which carry no names.
 */
export function orderHistory(order: HistorySource): HistoryEntry[] {
  if (order.events.length) {
    return [
      { label: "Placed", at: order.placedAt, by: order.resellerName },
      ...order.events.map((event) => ({
        label: eventLabel(event),
        at: event.at,
        by: event.actorName ?? (event.actorType === "owner" ? "Owner" : null),
      })),
    ];
  }
  const stamped: [string, string | null][] = [
    ["Placed", order.placedAt],
    ["Confirmed", order.confirmedAt],
    ["Delivered", order.deliveredAt],
    ["Paid", order.paidAt],
    ["Cancelled", order.cancelledAt],
  ];
  return stamped.flatMap(([label, at]) => (at ? [{ label, at, by: null }] : []));
}
