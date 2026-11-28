// Pure helpers for short-delivery reports, shared by the reseller's page and the dashboard.
import type {
  DiscrepancyInput,
  DiscrepancyParty,
  DiscrepancyStatus,
  DiscrepancyView,
  OrderLineView,
  Unit,
} from "./api-types";
import { formatDayTime, formatMoney, type Status } from "./format";
import { parseQuantity } from "./quantity";

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

const NOTE_LIMIT = 500;
const BLANK = "Enter the quantity received";
const NOTHING = "Change at least one received quantity to report a problem.";

/** Who did something, from the viewer's side: "You", "Your supplier", "The reseller". */
export function partyLabel(party: DiscrepancyParty, viewer: DiscrepancyParty): string {
  if (party === viewer) return "You";
  return viewer === "reseller" ? "Your supplier" : "The reseller";
}

/** A positive total is short, a negative one is over-delivery; neither shows a minus sign. */
export function shortfallText(total: number): string {
  if (total === 0) return "No difference in value";
  return `${formatMoney(Math.abs(total))} ${total > 0 ? "short" : "over"}`;
}

export function receivedText(line: { orderedQuantity: number; receivedQuantity: number; unit: Unit }): string {
  return `${line.receivedQuantity} of ${line.orderedQuantity} ${line.unit}`;
}

/** "The reseller reported this Fri 11:00 AM", then how it was answered, from the viewer's side. */
export function reportTimeline(report: DiscrepancyView, viewer: DiscrepancyParty): string[] {
  const lines = [`${partyLabel(report.filedBy, viewer)} reported this ${formatDayTime(report.filedAt)}`];
  if (report.resolvedBy && report.resolvedAt) {
    const did = report.status === "approved" ? "approved" : "declined";
    lines.push(`${partyLabel(report.resolvedBy, viewer)} ${did} this ${formatDayTime(report.resolvedAt)}`);
  }
  return lines;
}

/** What a read of the order's report came back with; null before it has. */
export type ReportRead = { report: DiscrepancyView } | { status: number } | null;

export type ReportState =
  | { kind: "loading" }
  | { kind: "none" }
  | { kind: "report"; report: DiscrepancyView }
  | { kind: "failed" };

/** 404 is "no report"; any other failed read is a failure the owner can retry. */
export function reportState(read: ReportRead): ReportState {
  if (read === null) return { kind: "loading" };
  if ("report" in read) return { kind: "report", report: read.report };
  return read.status === 404 ? { kind: "none" } : { kind: "failed" };
}

export function canFile(orderStatus: Status, hasReport: boolean): boolean {
  return orderStatus === "delivered" && !hasReport;
}

/** The other party answers an open report, once. */
export function canResolve(report: Pick<DiscrepancyView, "status" | "filedBy">, viewer: DiscrepancyParty): boolean {
  return report.status === "open" && report.filedBy !== viewer;
}

/** The API wants a JSON body even when there is no note. */
export function resolveBody(note: string): { note?: string } {
  const text = note.trim().slice(0, NOTE_LIMIT);
  return text ? { note: text } : {};
}

/** Every box starts at what was ordered; the reseller changes only what differs. */
export function receivedDefaults(lines: OrderedLine[]): Record<string, string> {
  return Object.fromEntries(lines.map((l) => [String(l.productId), String(l.quantity)]));
}

/**
 * Builds the request from what was typed. Only lines that differ from the order are sent; a
 * box left at the ordered quantity is a line the report does not mention. Zero is a valid
 * received quantity (nothing arrived), unlike on an order, where it means "not ordered".
 */
export function discrepancyInput(
  form: ReceivedForm,
  lines: OrderedLine[],
  idempotencyKey: string,
): { ok: true; input: DiscrepancyInput } | { ok: false; errors: FormErrors } {
  const errors: FormErrors = { lines: {} };
  const differing: DiscrepancyInput["lines"] = [];
  for (const line of lines) {
    const typed = form.received[String(line.productId)];
    if (typed === undefined) continue;
    if (typed.trim() === "") {
      errors.lines[String(line.productId)] = BLANK;
      continue;
    }
    const parsed = parseQuantity(typed, line.unit as Unit);
    if ("error" in parsed) errors.lines[String(line.productId)] = parsed.error;
    else if ((parsed.quantity ?? 0) !== line.quantity) differing.push({ productId: line.productId, receivedQuantity: parsed.quantity ?? 0 });
  }
  const note = form.note.trim();
  if (note.length > NOTE_LIMIT) errors.note = "Use 500 characters or fewer";
  if (Object.keys(errors.lines).length === 0 && !errors.note && differing.length === 0) errors.form = NOTHING;
  if (Object.keys(errors.lines).length || errors.note || errors.form) return { ok: false, errors };
  return { ok: true, input: { lines: differing, ...(note ? { note } : {}), idempotencyKey } };
}

/**
 * Maps the API's field errors onto the form. A path indexes into what was sent, so
 * `lines[1].receivedQuantity` is the second line sent, which `sent` turns back into a product.
 */
export function discrepancyApiErrors(fields: Record<string, string>, sent: { productId: number }[]): FormErrors {
  const errors: FormErrors = { lines: {} };
  for (const [path, message] of Object.entries(fields)) {
    const index = /^lines\[(\d+)\]/.exec(path);
    const productId = index ? sent[Number(index[1])]?.productId : undefined;
    if (productId !== undefined) errors.lines[String(productId)] = message;
    else if (path === "note") errors.note = message;
    else errors.form ??= message;
  }
  return errors;
}
