import { describe, expect, test } from "vitest";
import type { OrderEventView } from "../api-types";
import {
  countByStatus,
  defaultDeliveryDate,
  itemsSummary,
  orderHistory,
  previewQuery,
  sortOrders,
  stepDeliveryDay,
  summarize,
} from "./orders";

const o = (status: "placed" | "confirmed" | "delivered" | "cancelled", placedAt: string, total = 100) => ({
  status,
  placedAt,
  total,
  deliveryDate: "2026-11-27",
});

describe("orders list", () => {
  test("sorts placed, confirmed, delivered, cancelled, newest first within each", () => {
    const sorted = sortOrders([
      o("confirmed", "2026-11-26T01:00:00Z"),
      o("cancelled", "2026-11-26T05:00:00Z"),
      o("placed", "2026-11-26T00:00:00Z"),
      o("placed", "2026-11-26T02:00:00Z"),
    ]);
    expect(sorted.map((x) => `${x.status}@${x.placedAt.slice(11, 13)}`)).toEqual([
      "placed@02",
      "placed@00",
      "confirmed@01",
      "cancelled@05",
    ]);
  });

  test("summary totals everything but cancelled and counts what needs confirmation", () => {
    const orders = [o("placed", "a", 2200), o("confirmed", "b", 2160), o("confirmed", "c", 920), o("cancelled", "d", 500)];
    expect(summarize(orders)).toEqual({ main: "4 orders · ₱5,280.00", warning: "1 needs confirmation" });
    expect(countByStatus(orders)).toEqual({ all: 4, placed: 1, confirmed: 2, delivered: 0, cancelled: 1 });
  });

  test("date arrows step over days the business does not deliver", () => {
    const monWedFri = [1, 3, 5];
    expect(stepDeliveryDay("2026-11-27", monWedFri, 1)).toBe("2026-11-30"); // Fri to Mon
    expect(stepDeliveryDay("2026-11-30", monWedFri, -1)).toBe("2026-11-27"); // Mon to Fri
  });

  test("opens on the earliest open delivery date, else the next delivery day", () => {
    expect(defaultDeliveryDate([{ deliveryDate: "2026-11-28" }, { deliveryDate: "2026-11-27" }], [1, 2, 3, 4, 5, 6])).toBe(
      "2026-11-27",
    );
    expect(defaultDeliveryDate([], [1, 2, 3, 4, 5, 6], "2026-12-01")).toBe("2026-12-01");
    // Without the API's answer: Saturday 28 Nov 2026 in Manila, Monday to Saturday: Monday.
    expect(defaultDeliveryDate([], [1, 2, 3, 4, 5, 6], undefined, new Date("2026-11-28T02:00:00Z"))).toBe("2026-11-30");
  });

  test("items read as one line of quantity, unit and product", () => {
    expect(
      itemsSummary([
        { productName: "Hamonado", unit: "pack", quantity: 10 },
        { productName: "Tocino", unit: "kg", quantity: 5 },
      ]),
    ).toBe("10 pack Hamonado, 5 kg Tocino");
  });

  test("the preview query repeats deliveryDays and trims seconds from the cut-off", () => {
    expect(previewQuery("16:00:00", [1, 4])).toBe("cutoff=16%3A00&deliveryDays=1&deliveryDays=4");
  });
});

const placed = {
  placedAt: "2026-11-26T02:00:00Z",
  resellerName: "Aling Rosa",
  confirmedAt: null,
  deliveredAt: null,
  cancelledAt: null,
  paidAt: null,
};

const event = (
  at: string,
  fromStatus: OrderEventView["fromStatus"],
  toStatus: OrderEventView["toStatus"],
  paid: boolean,
  actorType: OrderEventView["actorType"] = "owner",
  actorName: string | null = "Nena Santos",
): OrderEventView => ({ at, fromStatus, toStatus, paid, actorType, actorName });

describe("order history", () => {
  test("lists the placement, then each event with its label and who did it", () => {
    const events = [
      event("2026-11-26T03:00:00Z", "placed", "confirmed", false),
      event("2026-11-27T03:00:00Z", "confirmed", "delivered", false),
      event("2026-11-28T03:00:00Z", "delivered", "delivered", true),
    ];

    expect(orderHistory({ ...placed, events })).toEqual([
      { label: "Placed", at: "2026-11-26T02:00:00Z", by: "Aling Rosa" },
      { label: "Confirmed", at: "2026-11-26T03:00:00Z", by: "Nena Santos" },
      { label: "Delivered", at: "2026-11-27T03:00:00Z", by: "Nena Santos" },
      { label: "Marked paid", at: "2026-11-28T03:00:00Z", by: "Nena Santos" },
    ]);
  });

  test("delivering as paid is one entry", () => {
    const events = [
      event("2026-11-26T03:00:00Z", "placed", "confirmed", false),
      event("2026-11-27T03:00:00Z", "confirmed", "delivered", true),
    ];

    expect(orderHistory({ ...placed, events }).map((e) => e.label)).toEqual([
      "Placed",
      "Confirmed",
      "Delivered and paid",
    ]);
  });

  test("a cancellation names whoever did it, the reseller or the owner", () => {
    const byReseller = [event("2026-11-26T03:00:00Z", "placed", "cancelled", false, "reseller", "Aling Rosa")];
    const byOwner = [event("2026-11-26T03:00:00Z", "confirmed", "cancelled", false)];

    expect(orderHistory({ ...placed, events: byReseller }).at(-1)).toEqual({
      label: "Cancelled",
      at: "2026-11-26T03:00:00Z",
      by: "Aling Rosa",
    });
    expect(orderHistory({ ...placed, events: byOwner }).at(-1)?.by).toBe("Nena Santos");
  });

  test("an owner with no display name reads Owner", () => {
    const events = [event("2026-11-26T03:00:00Z", "placed", "confirmed", false, "owner", null)];

    expect(orderHistory({ ...placed, events }).at(-1)?.by).toBe("Owner");
  });

  test("without events, it falls back to the order's timestamps and names no one", () => {
    const order = {
      ...placed,
      confirmedAt: "2026-11-26T03:00:00Z",
      deliveredAt: "2026-11-27T03:00:00Z",
      paidAt: "2026-11-28T03:00:00Z",
      events: [],
    };

    expect(orderHistory(order)).toEqual([
      { label: "Placed", at: "2026-11-26T02:00:00Z", by: null },
      { label: "Confirmed", at: "2026-11-26T03:00:00Z", by: null },
      { label: "Delivered", at: "2026-11-27T03:00:00Z", by: null },
      { label: "Paid", at: "2026-11-28T03:00:00Z", by: null },
    ]);
    expect(orderHistory({ ...placed, cancelledAt: "2026-11-26T04:00:00Z", events: [] }).map((e) => e.label)).toEqual([
      "Placed",
      "Cancelled",
    ]);
  });
});
