// Server-side calls from the web pod to the API with the reseller's token (ADR 0010).
import type { CatalogView, OrderView, Problem, QuoteSummaryView, QuoteView } from "../api-types";

export type Fetch = typeof fetch;

export function apiBase(): string {
  return (process.env.COUNTY_API_URL ?? "http://localhost:8080/api").replace(/\/$/, "");
}

export type Result<T> = { ok: true; status: number; body: T } | { ok: false; status: number; problem: Problem };

async function call<T>(fetchImpl: Fetch, path: string, init?: RequestInit): Promise<Result<T>> {
  const response = await fetchImpl(`${apiBase()}${path}`, {
    ...init,
    cache: "no-store",
    headers: { accept: "application/json", ...(init?.body ? { "content-type": "application/json" } : {}) },
  });
  const body = await response.json().catch(() => ({}));
  if (response.ok) return { ok: true, status: response.status, body: body as T };
  return { ok: false, status: response.status, problem: { status: response.status, ...body } };
}

const tokenPath = (token: string) => `/order/${encodeURIComponent(token)}`;

export const resellerApi = (fetchImpl: Fetch = fetch) => ({
  catalog: (token: string) => call<CatalogView>(fetchImpl, tokenPath(token)),
  order: (token: string, id: number) => call<OrderView>(fetchImpl, `${tokenPath(token)}/orders/${id}`),
  place: (token: string, body: { lines: { productId: number; quantity: number }[]; note?: string; idempotencyKey: string }) =>
    call<OrderView>(fetchImpl, `${tokenPath(token)}/orders`, { method: "POST", body: JSON.stringify(body) }),
  cancel: (token: string, id: number) =>
    call<OrderView>(fetchImpl, `${tokenPath(token)}/orders/${id}/cancel`, { method: "POST" }),
  /** Sent quotes the reseller can still accept, newest first. */
  quotes: (token: string) => call<QuoteSummaryView[]>(fetchImpl, `${tokenPath(token)}/quotes`),
  quote: (token: string, id: number) => call<QuoteView>(fetchImpl, `${tokenPath(token)}/quotes/${id}`),
  /** 201 with the new order, or 200 with the order the quote already placed. */
  accept: (token: string, id: number, idempotencyKey: string) =>
    call<OrderView>(fetchImpl, `${tokenPath(token)}/quotes/${id}/accept`, {
      method: "POST",
      body: JSON.stringify({ idempotencyKey }),
    }),
  decline: (token: string, id: number) =>
    call<QuoteView>(fetchImpl, `${tokenPath(token)}/quotes/${id}/decline`, { method: "POST" }),
});
