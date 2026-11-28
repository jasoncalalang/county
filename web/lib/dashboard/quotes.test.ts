import { describe, expect, test } from "vitest";
import { apiErrors, countQuotes, defaultExpiry, quoteHistory, quoteInput, quoteLink, quoteShareMessage } from "./quotes";

const draft = { resellerId: "7", quantities: { "1": "10", "3": "", "4": "0" }, expiresOn: "2026-12-05", note: "" };

describe("quoteInput", () => {
  test("keeps the products with a quantity", () => {
    expect(quoteInput(draft, "2026-11-28")).toEqual({
      ok: true,
      input: { resellerId: 7, lines: [{ productId: 1, quantity: 10 }], expiresOn: "2026-12-05" },
    });
  });

  test("sends a trimmed note only when there is one", () => {
    const checked = quoteInput({ ...draft, note: "  promo price  " }, "2026-11-28");
    expect(checked.ok && checked.input.note).toBe("promo price");
  });

  test("names every field at fault", () => {
    expect(quoteInput({ resellerId: "", quantities: {}, expiresOn: "2026-11-27", note: "" }, "2026-11-28")).toEqual({
      ok: false,
      errors: { resellerId: "Choose a reseller", lines: "Add at least one product", expiresOn: "Choose today or a later day" },
    });
  });

  test("a quantity that is not a whole number is marked on its row", () => {
    const checked = quoteInput({ ...draft, quantities: { "1": "2.5", "3": "4" } }, "2026-11-28");
    expect(checked).toEqual({ ok: false, errors: { quantities: { "1": "Enter a whole number from 0 to 9999" } } });
  });

  test("a kg product takes a decimal quantity", () => {
    const checked = quoteInput({ ...draft, quantities: { "1": "10", "3": "1.5" } }, "2026-11-28", { "1": "pack", "3": "kg" });
    expect(checked).toEqual({
      ok: true,
      input: {
        resellerId: 7,
        lines: [
          { productId: 1, quantity: 10 },
          { productId: 3, quantity: 1.5 },
        ],
        expiresOn: "2026-12-05",
      },
    });
  });

  test("a fraction of a pack, or four decimals of kg, is marked on its row", () => {
    const checked = quoteInput({ ...draft, quantities: { "1": "1.5", "3": "1.2345" } }, "2026-11-28", { "1": "pack", "3": "kg" });
    expect(checked).toEqual({
      ok: false,
      errors: {
        quantities: {
          "1": "Enter a whole number from 0 to 9999",
          "3": "Enter a number from 0 to 9999, with up to 3 decimals",
        },
      },
    });
  });

  test("today is a valid last day", () => {
    expect(quoteInput({ ...draft, expiresOn: "2026-11-28" }, "2026-11-28").ok).toBe(true);
  });
});

test("apiErrors puts a line's message on its product", () => {
  const sent = [{ productId: 1 }, { productId: 3 }];
  expect(apiErrors({ "lines[1].quantity": "Too many", expiresOn: "Choose today or a later day", x: "?" }, sent)).toEqual({
    quantities: { "3": "Too many" },
    expiresOn: "Choose today or a later day",
    form: "?",
  });
});

test("countQuotes counts each status", () => {
  const counts = countQuotes([{ status: "sent" }, { status: "sent" }, { status: "draft" }]);
  expect(counts).toMatchObject({ all: 3, sent: 2, draft: 1, accepted: 0 });
});

test("defaultExpiry is seven days from today in Manila", () => {
  // 20:00 UTC on 27 Nov is 04:00 on 28 Nov in Manila.
  expect(defaultExpiry(new Date("2026-11-27T20:00:00Z"))).toBe("2026-12-05");
});

test("the quote link and message point at the reseller's order link", () => {
  const reseller = { name: "Aling Rosa", orderLink: "https://county.example/order/abc" };
  expect(quoteLink(reseller.orderLink, 12)).toBe("https://county.example/order/abc/quotes/12");
  expect(quoteShareMessage(reseller, "Nena's", { id: 12, number: 3 })).toBe(
    "Hi Aling Rosa, here is quote #Q3 from Nena's: https://county.example/order/abc/quotes/12",
  );
});

test("quoteHistory lists what happened, in order", () => {
  expect(
    quoteHistory({ createdAt: "a", sentAt: "b", acceptedAt: null, declinedAt: "c", cancelledAt: null }),
  ).toEqual([
    ["Created", "a"],
    ["Sent", "b"],
    ["Declined", "c"],
  ]);
});
