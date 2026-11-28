// How a typed quantity is read, for the reseller page and the new-quote form (ADR 0017). The
// API applies the same rules; this only spares a round trip.
import type { Unit } from "./api-types";

const MAXIMUM = 9999;
const WHOLE = "Enter a whole number from 0 to 9999";
const DECIMAL = "Enter a number from 0 to 9999, with up to 3 decimals";

/** `quantity` is null when nothing is ordered: an empty box or zero. */
export type ParsedQuantity = { quantity: number | null } | { error: string };

/** Digits and at most one decimal point; whole numbers only unless the unit is kg. */
export function parseQuantity(typed: string, unit?: Unit): ParsedQuantity {
  const text = typed.trim();
  const fractional = unit === "kg";
  const error = { error: fractional ? DECIMAL : WHOLE };
  if (text === "") return { quantity: null };
  if (!/^\d{1,4}(\.\d{1,3})?$/.test(text)) return error;
  const quantity = Number(text);
  if (quantity > MAXIMUM || (!fractional && !Number.isInteger(quantity))) return error;
  return { quantity: quantity === 0 ? null : quantity };
}

/** The attributes of a quantity input: decimals for kg, whole numbers for the rest. */
export function quantityAttrs(unit?: Unit): { inputMode: "numeric" | "decimal"; step: "1" | "0.001" } {
  return unit === "kg" ? { inputMode: "decimal", step: "0.001" } : { inputMode: "numeric", step: "1" };
}
