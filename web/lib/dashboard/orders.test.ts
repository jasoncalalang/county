import { describe, expect, test } from "vitest";
import {
  countByStatus,
  defaultDeliveryDate,
  itemsSummary,
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
