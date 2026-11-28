// What every reseller route sends back: the headers, the redirect, the inactive link and the
// order id in the URL. Shared by handlers.ts and discrepancy.ts.
import { inactivePage } from "./render";

const HEADERS = {
  "content-type": "text/html; charset=utf-8",
  "cache-control": "no-store",
  "referrer-policy": "no-referrer",
  "x-robots-tag": "noindex",
};

export function htmlResponse(body: string, status = 200, cacheControl = "no-store"): Response {
  return new Response(body, { status, headers: { ...HEADERS, "cache-control": cacheControl } });
}

// Back from the confirmation must return to the same form, with the same idempotency key, so
// that a resubmit returns the first order (spec 4.2). The browser's back/forward cache does
// that, but it skips pages sent with no-store, so the price list is sent with max-age=0 instead:
// still fetched again on every ordinary visit, and private to the phone.
export const PRICE_LIST_CACHE = "private, max-age=0";

export function redirect(location: string): Response {
  // 303 so that the browser follows with a GET and a reload does not post again.
  return new Response(null, { status: 303, headers: { ...HEADERS, location } });
}

export const inactive = () => htmlResponse(inactivePage(), 404);

/** Order ids in the URL are positive integers; anything else is not an order. */
export function orderId(raw: string): number | null {
  return /^[1-9]\d{0,17}$/.test(raw) ? Number(raw) : null;
}

export const base = (token: string) => `/order/${encodeURIComponent(token)}`;
