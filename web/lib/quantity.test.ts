import { describe, expect, test } from "vitest";
import { parseQuantity, quantityAttrs } from "./quantity";

const WHOLE = { error: "Enter a whole number from 0 to 9999" };
const DECIMAL = { error: "Enter a number from 0 to 9999, with up to 3 decimals" };

describe("parseQuantity", () => {
  test("a kg quantity may have up to three decimals", () => {
    expect(parseQuantity("1.5", "kg")).toEqual({ quantity: 1.5 });
    expect(parseQuantity("0.333", "kg")).toEqual({ quantity: 0.333 });
    expect(parseQuantity("0.001", "kg")).toEqual({ quantity: 0.001 });
    expect(parseQuantity("9999", "kg")).toEqual({ quantity: 9999 });
    expect(parseQuantity(" 2 ", "kg")).toEqual({ quantity: 2 });
  });

  test("a pack or a piece is a whole number", () => {
    expect(parseQuantity("10", "pack")).toEqual({ quantity: 10 });
    expect(parseQuantity("2.000", "pc")).toEqual({ quantity: 2 });
    expect(parseQuantity("1.5", "pack")).toEqual(WHOLE);
    expect(parseQuantity("0.5", "pc")).toEqual(WHOLE);
  });

  test("a product whose unit is not known is read as whole", () => {
    expect(parseQuantity("3")).toEqual({ quantity: 3 });
    expect(parseQuantity("2.5")).toEqual(WHOLE);
  });

  test("an empty box or zero means not ordered", () => {
    for (const typed of ["", "   ", "0", "0.0", "0.000"]) {
      expect(parseQuantity(typed, "kg"), typed).toEqual({ quantity: null });
    }
    expect(parseQuantity("", "pack")).toEqual({ quantity: null });
    expect(parseQuantity("0", "pack")).toEqual({ quantity: null });
  });

  test("anything else is refused with a message", () => {
    for (const typed of ["1.2345", "-1", "1e3", "1,5", "<b>1</b>", "10000", "9999.001", "abc", ".5", "1.", "+1", "1 2", "0x10"]) {
      expect(parseQuantity(typed, "kg"), typed).toEqual(DECIMAL);
    }
    for (const typed of ["-1", "1e3", "10000", "abc", "<b>1</b>"]) {
      expect(parseQuantity(typed, "pack"), typed).toEqual(WHOLE);
    }
  });
});

describe("quantityAttrs", () => {
  test("a kg input takes decimals, every other input whole numbers", () => {
    expect(quantityAttrs("kg")).toEqual({ inputMode: "decimal", step: "0.001" });
    expect(quantityAttrs("pack")).toEqual({ inputMode: "numeric", step: "1" });
    expect(quantityAttrs("pc")).toEqual({ inputMode: "numeric", step: "1" });
    expect(quantityAttrs()).toEqual({ inputMode: "numeric", step: "1" });
  });
});
