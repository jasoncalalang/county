// Pure helpers for the dashboard's quote screens, kept apart so they can be tested.
import type { QuoteStatus, QuoteSummaryView, QuoteView, ResellerView, Unit } from "../api-types";
import { manilaDate, quoteNumber } from "../format";

export const QUOTE_FILTERS: (QuoteStatus | "all")[] = ["all", "draft", "sent", "accepted", "declined", "expired", "cancelled"];

/** Days a new quote stays open unless the owner picks another last day. */
export const DEFAULT_QUOTE_DAYS = 7;

export function countQuotes(quotes: Pick<QuoteSummaryView, "status">[]): Record<QuoteStatus | "all", number> {
  const counts = { all: quotes.length, draft: 0, sent: 0, accepted: 0, declined: 0, expired: 0, cancelled: 0 };
  for (const q of quotes) counts[q.status] += 1;
  return counts;
}

/** Today in Manila plus `days`, as `YYYY-MM-DD`: the default last day to accept. */
export function defaultExpiry(now: Date = new Date(), days = DEFAULT_QUOTE_DAYS): string {
  const d = new Date(`${manilaDate(now)}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** The quote's page on the reseller's order link. */
export function quoteLink(orderLink: string, quoteId: number): string {
  return `${orderLink.replace(/\/$/, "")}/quotes/${quoteId}`;
}

export function quoteShareMessage(
  reseller: Pick<ResellerView, "name" | "orderLink">,
  business: string,
  quote: Pick<QuoteView, "id" | "number">,
): string {
  return `Hi ${reseller.name}, here is quote ${quoteNumber(quote.number)} from ${business}: ${quoteLink(reseller.orderLink, quote.id)}`;
}

export interface QuoteDraft {
  resellerId: string;
  /** Quantities as typed, by product id. */
  quantities: Record<string, string>;
  expiresOn: string;
  note: string;
}

export type DraftErrors = Partial<Record<"resellerId" | "lines" | "expiresOn" | "note" | "form", string>> & {
  /** By product id. */
  quantities?: Record<string, string>;
};

/**
 * Checks the new-quote form before it is sent. The API checks again; this only spares a round
 * trip for what the owner can see is wrong. `today` is the business date in Manila.
 */
export function quoteInput(
  draft: QuoteDraft,
  today: string,
  units: Record<string, Unit> = {},
): { ok: true; input: { resellerId: number; lines: { productId: number; quantity: number }[]; note?: string; expiresOn: string } } | { ok: false; errors: DraftErrors } {
  void units;
  const errors: DraftErrors = {};
  const quantities: Record<string, string> = {};
  const lines: { productId: number; quantity: number }[] = [];
  for (const [id, raw] of Object.entries(draft.quantities)) {
    const typed = raw.trim();
    if (typed === "" || typed === "0") continue;
    if (!/^\d{1,4}$/.test(typed)) quantities[id] = "Enter a whole number from 0 to 9999";
    else lines.push({ productId: Number(id), quantity: Number(typed) });
  }
  if (Object.keys(quantities).length) errors.quantities = quantities;
  else if (lines.length === 0) errors.lines = "Add at least one product";
  else if (lines.length > 50) errors.lines = "A quote can have up to 50 products";
  if (!/^[1-9]\d*$/.test(draft.resellerId)) errors.resellerId = "Choose a reseller";
  if (!/^\d{4}-\d{2}-\d{2}$/.test(draft.expiresOn)) errors.expiresOn = "Choose the last day to accept";
  else if (draft.expiresOn < today) errors.expiresOn = "Choose today or a later day";
  const note = draft.note.trim();
  if (note.length > 500) errors.note = "Use 500 characters or fewer";
  if (Object.keys(errors).length) return { ok: false, errors };
  return {
    ok: true,
    input: { resellerId: Number(draft.resellerId), lines, expiresOn: draft.expiresOn, ...(note ? { note } : {}) },
  };
}

/**
 * Maps the API's field errors onto the form. Paths index into what was sent, so
 * `lines[1].productId` is the second line sent, which `sent` turns back into a product id.
 */
export function apiErrors(fields: Record<string, string>, sent: { productId: number }[]): DraftErrors {
  const errors: DraftErrors = {};
  for (const [path, message] of Object.entries(fields)) {
    const line = /^lines\[(\d+)\]/.exec(path);
    const productId = line ? sent[Number(line[1])]?.productId : undefined;
    if (productId !== undefined) errors.quantities = { ...errors.quantities, [String(productId)]: message };
    else if (path === "resellerId" || path === "expiresOn" || path === "note" || path === "lines") errors[path] = message;
    else errors.form = message;
  }
  return errors;
}

/** "Created", "Sent", then how it ended, each with its time, for the detail's history. */
export function quoteHistory(q: Pick<QuoteView, "createdAt" | "sentAt" | "acceptedAt" | "declinedAt" | "cancelledAt">): [string, string][] {
  return (
    [
      ["Created", q.createdAt],
      ["Sent", q.sentAt],
      ["Accepted", q.acceptedAt],
      ["Declined", q.declinedAt],
      ["Cancelled", q.cancelledAt],
    ] as [string, string | null][]
  ).filter((entry): entry is [string, string] => Boolean(entry[1]));
}
