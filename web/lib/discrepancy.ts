// Pure helpers for short-delivery reports, shared by the reseller's page and the dashboard.
import type {
  DiscrepancyInput,
  DiscrepancyParty,
  DiscrepancyStatus,
  DiscrepancyView,
  OrderLineView,
  Unit,
} from "./api-types";
import type { Status } from "./format";

export const DISCREPANCY_STATUS_LABEL: Record<DiscrepancyStatus, string> = {
  open: "Open",
  approved: "Approved",
  declined: "Declined",
};

export interface ReceivedForm {
  /** Received quantities as typed, by product id. */
  received: Record<string, string>;
  note: string;
}

export interface FormErrors {
  /** By product id. */
  lines: Record<string, string>;
  note?: string;
  form?: string;
}

type OrderedLine = Pick<OrderLineView, "productId" | "unit" | "quantity">;

export function partyLabel(party: DiscrepancyParty, viewer: DiscrepancyParty): string {
  throw new Error("not implemented");
}

export function shortfallText(total: number): string {
  throw new Error("not implemented");
}

export function receivedText(line: { orderedQuantity: number; receivedQuantity: number; unit: Unit }): string {
  throw new Error("not implemented");
}

export function canFile(orderStatus: Status, hasReport: boolean): boolean {
  throw new Error("not implemented");
}

export function canResolve(report: Pick<DiscrepancyView, "status" | "filedBy">, viewer: DiscrepancyParty): boolean {
  throw new Error("not implemented");
}

export function resolveBody(note: string): { note?: string } {
  throw new Error("not implemented");
}

export function receivedDefaults(lines: OrderedLine[]): Record<string, string> {
  throw new Error("not implemented");
}

export function discrepancyInput(
  form: ReceivedForm,
  lines: OrderedLine[],
  idempotencyKey: string,
): { ok: true; input: DiscrepancyInput } | { ok: false; errors: FormErrors } {
  throw new Error("not implemented");
}

export function discrepancyApiErrors(fields: Record<string, string>, sent: { productId: number }[]): FormErrors {
  throw new Error("not implemented");
}
