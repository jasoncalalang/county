import { describe, expect, test } from "vitest";
import {
  canFile,
  canResolve,
  discrepancyApiErrors,
  discrepancyInput,
  partyLabel,
  receivedDefaults,
  receivedText,
  resolveBody,
  shortfallText,
  DISCREPANCY_STATUS_LABEL,
} from "./discrepancy";

const KEY = "0b6f2c1e-8a3d-4f5b-9c7e-1d2e3f4a5b6c";
const lines = [
  { productId: 1, unit: "pack" as const, quantity: 10 },
  { productId: 3, unit: "kg" as const, quantity: 5 },
];

const WHOLE = "Enter a whole number from 0 to 9999";
const DECIMAL = "Enter a number from 0 to 9999, with up to 3 decimals";
const NOTHING = "Change at least one received quantity to report a problem.";
const BLANK = "Enter the quantity received";

describe("discrepancyInput", () => {
  test("sends only the lines that differ from the order", () => {
    const result = discrepancyInput({ received: { "1": "8", "3": "5" }, note: "" }, lines, KEY);

    expect(result).toEqual({ ok: true, input: { lines: [{ productId: 1, receivedQuantity: 8 }], idempotencyKey: KEY } });
  });

  test("zero means nothing arrived, more than ordered is over-delivery, a kg line takes decimals", () => {
    const result = discrepancyInput({ received: { "1": "0", "3": "5.25" }, note: "" }, lines, KEY);

    expect(result).toEqual({
      ok: true,
      input: {
        lines: [
          { productId: 1, receivedQuantity: 0 },
          { productId: 3, receivedQuantity: 5.25 },
        ],
        idempotencyKey: KEY,
      },
    });
    const over = discrepancyInput({ received: { "1": "12", "3": "5" }, note: "" }, lines, KEY);
    expect(over).toMatchObject({ ok: true, input: { lines: [{ productId: 1, receivedQuantity: 12 }] } });
  });

  test("a quantity written differently but equal to the order is not a difference", () => {
    expect(discrepancyInput({ received: { "1": "10", "3": "5.000" }, note: "" }, lines, KEY)).toEqual({
      ok: false,
      errors: { lines: {}, form: NOTHING },
    });
  });

  test("the note is trimmed and left out when empty", () => {
    const withNote = discrepancyInput({ received: { "1": "8" }, note: "  two were crushed \n" }, lines, KEY);
    expect(withNote).toMatchObject({ ok: true, input: { note: "two were crushed" } });
    const blank = discrepancyInput({ received: { "1": "8" }, note: "   " }, lines, KEY);
    expect(blank).toMatchObject({ ok: true });
    expect("note" in (blank as { input: object }).input).toBe(false);
  });

  test("a product that is not on the order is ignored", () => {
    const result = discrepancyInput({ received: { "1": "8", "99": "4" }, note: "" }, lines, KEY);

    expect(result).toMatchObject({ ok: true, input: { lines: [{ productId: 1, receivedQuantity: 8 }] } });
  });

  test("each bad quantity gets its own message, all at once, and nothing is sent", () => {
    const result = discrepancyInput(
      { received: { "1": "1.5", "3": "4.1234" }, note: "" },
      lines,
      KEY,
    );

    expect(result).toEqual({ ok: false, errors: { lines: { "1": WHOLE, "3": DECIMAL } } });
  });

  test("a blank box, text and a number above 9999 are refused", () => {
    expect(discrepancyInput({ received: { "1": "", "3": "abc" }, note: "" }, lines, KEY)).toEqual({
      ok: false,
      errors: { lines: { "1": BLANK, "3": DECIMAL } },
    });
    expect(discrepancyInput({ received: { "1": "10000" }, note: "" }, lines, KEY)).toEqual({
      ok: false,
      errors: { lines: { "1": WHOLE } },
    });
    expect(discrepancyInput({ received: { "1": "-1" }, note: "" }, lines, KEY)).toEqual({
      ok: false,
      errors: { lines: { "1": WHOLE } },
    });
  });

  test("when nothing differs the form says so, once", () => {
    expect(discrepancyInput({ received: { "1": "10", "3": "5" }, note: "" }, lines, KEY)).toEqual({
      ok: false,
      errors: { lines: {}, form: NOTHING },
    });
    expect(discrepancyInput({ received: {}, note: "" }, lines, KEY)).toEqual({
      ok: false,
      errors: { lines: {}, form: NOTHING },
    });
  });

  test("a note over 500 characters is refused", () => {
    const result = discrepancyInput({ received: { "1": "8" }, note: "x".repeat(501) }, lines, KEY);

    expect(result).toEqual({ ok: false, errors: { lines: {}, note: "Use 500 characters or fewer" } });
    expect(discrepancyInput({ received: { "1": "8" }, note: "x".repeat(500) }, lines, KEY)).toMatchObject({ ok: true });
  });
});

describe("receivedDefaults", () => {
  test("starts every box at what was ordered", () => {
    expect(receivedDefaults(lines)).toEqual({ "1": "10", "3": "5" });
    expect(receivedDefaults([{ productId: 4, unit: "kg", quantity: 1.5 }])).toEqual({ "4": "1.5" });
  });
});

describe("discrepancyApiErrors", () => {
  const sent = [{ productId: 1 }, { productId: 3 }];

  test("a line error goes back to the product it was sent for", () => {
    expect(
      discrepancyApiErrors(
        {
          "lines[1].receivedQuantity": "Received quantity matches what was ordered",
          "lines[0].productId": "This product is not on the order",
        },
        sent,
      ),
    ).toEqual({
      lines: { "3": "Received quantity matches what was ordered", "1": "This product is not on the order" },
    });
  });

  test("the note and the report as a whole have their own places", () => {
    expect(discrepancyApiErrors({ note: "size must be between 0 and 500" }, sent)).toEqual({
      lines: {},
      note: "size must be between 0 and 500",
    });
    expect(discrepancyApiErrors({ lines: "Shortfall is too large" }, sent)).toEqual({
      lines: {},
      form: "Shortfall is too large",
    });
  });

  test("a path that names nothing on the form becomes the form message", () => {
    expect(discrepancyApiErrors({ idempotencyKey: "must not be null" }, sent)).toEqual({
      lines: {},
      form: "must not be null",
    });
    expect(discrepancyApiErrors({}, sent)).toEqual({ lines: {} });
  });
});

describe("labels", () => {
  test("statuses", () => {
    expect(DISCREPANCY_STATUS_LABEL).toEqual({ open: "Open", approved: "Approved", declined: "Declined" });
  });

  test("a party is named from the viewer's side", () => {
    expect(partyLabel("reseller", "reseller")).toBe("You");
    expect(partyLabel("owner", "owner")).toBe("You");
    expect(partyLabel("owner", "reseller")).toBe("Your supplier");
    expect(partyLabel("reseller", "owner")).toBe("The reseller");
  });

  test("a shortfall reads as short, and over-delivery reads as over, never as a negative", () => {
    expect(shortfallText(240)).toBe("₱240.00 short");
    expect(shortfallText(25)).toBe("₱25.00 short");
    expect(shortfallText(-240)).toBe("₱240.00 over");
    expect(shortfallText(0)).toBe("No difference in value");
    expect(shortfallText(-1234.5)).not.toContain("-");
  });

  test("what arrived, against what was ordered", () => {
    expect(receivedText({ orderedQuantity: 10, receivedQuantity: 8, unit: "pack" })).toBe("8 of 10 pack");
    expect(receivedText({ orderedQuantity: 1.5, receivedQuantity: 0, unit: "kg" })).toBe("0 of 1.5 kg");
    expect(receivedText({ orderedQuantity: 10, receivedQuantity: 12, unit: "pc" })).toBe("12 of 10 pc");
  });
});

describe("who may do what", () => {
  test("only a delivered order with no report can be reported", () => {
    expect(canFile("delivered", false)).toBe(true);
    expect(canFile("delivered", true)).toBe(false);
    for (const status of ["placed", "confirmed", "cancelled"] as const) expect(canFile(status, false)).toBe(false);
  });

  test("the other party answers an open report, once", () => {
    expect(canResolve({ status: "open", filedBy: "reseller" }, "owner")).toBe(true);
    expect(canResolve({ status: "open", filedBy: "owner" }, "reseller")).toBe(true);
    expect(canResolve({ status: "open", filedBy: "reseller" }, "reseller")).toBe(false);
    expect(canResolve({ status: "open", filedBy: "owner" }, "owner")).toBe(false);
    expect(canResolve({ status: "approved", filedBy: "owner" }, "reseller")).toBe(false);
    expect(canResolve({ status: "declined", filedBy: "reseller" }, "owner")).toBe(false);
  });

  test("the resolve body is a JSON object, with the note only when there is one", () => {
    expect(resolveBody("")).toEqual({});
    expect(resolveBody("   ")).toEqual({});
    expect(resolveBody("  fine  ")).toEqual({ note: "fine" });
    expect(resolveBody("x".repeat(600)).note).toHaveLength(500);
  });
});
