// How a typed quantity is read, for the reseller page and the new-quote form (ADR 0017).
import type { Unit } from "./api-types";

/** `quantity` is null when nothing is ordered: an empty box or zero. */
export type ParsedQuantity = { quantity: number | null } | { error: string };

export function parseQuantity(typed: string, unit?: Unit): ParsedQuantity {
  void typed;
  void unit;
  throw new Error("not implemented");
}

export function quantityAttrs(unit?: Unit): { inputMode: "numeric" | "decimal"; step: "1" | "0.001" } {
  void unit;
  throw new Error("not implemented");
}
