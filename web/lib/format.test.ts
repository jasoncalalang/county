import { describe, expect, test } from "vitest";
import {
  formatClock,
  formatLongDate,
  formatMobile,
  formatMoney,
  formatPlaced,
  formatShortDate,
} from "./format";

describe("format", () => {
  test("money is pesos with centavos and grouping", () => {
    expect(formatMoney(2800)).toBe("₱2,800.00");
    expect(formatMoney(1234.5)).toBe("₱1,234.50");
  });

  test("delivery dates never shift a day", () => {
    expect(formatShortDate("2026-11-04")).toBe("Wed, 4 Nov");
    expect(formatLongDate("2026-11-05")).toBe("Thu, 5 Nov 2026");
  });

  test("cut-off clock reads as a 12-hour time", () => {
    expect(formatClock("16:00:00")).toBe("4:00 PM");
    expect(formatClock("00:30")).toBe("12:30 AM");
    expect(formatClock("12:05:00")).toBe("12:05 PM");
  });

  test("placed time drops the weekday only on the same Manila day", () => {
    const now = new Date("2026-11-05T03:00:00Z"); // 11:00 AM Thu in Manila
    expect(formatPlaced("2026-11-05T01:12:00Z", now)).toBe("9:12 AM");
    expect(formatPlaced("2026-11-04T07:40:00Z", now)).toBe("Wed 3:40 PM");
  });

  test("mobile numbers display in local form", () => {
    expect(formatMobile("+639171234567")).toBe("0917 123 4567");
  });
});
