// Pure helpers for the dashboard's order screens, kept apart so they can be tested.
import type { OrderSummaryView } from "../api-types";
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
 * order not yet delivered or cancelled, else the first delivery day after today.
 * GUESS: the API has no "next delivery date" for the owner, so the fallback is the next
 * configured delivery day after today in Manila; it ignores the cut-off.
 */
export function defaultDeliveryDate(
  open: Pick<OrderSummaryView, "deliveryDate">[],
  deliveryDays: number[],
  now: Date = new Date(),
): string {
  const earliest = open.map((o) => o.deliveryDate).sort()[0];
  return earliest ?? stepDeliveryDay(manilaDate(now), deliveryDays, 1);
}
