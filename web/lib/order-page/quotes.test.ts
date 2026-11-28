import { beforeEach, describe, expect, test } from "vitest";
import type { CatalogView, OrderView, QuoteSummaryView, QuoteView } from "../api-types";
import { acceptQuote, declineQuote, showPriceList, showQuote } from "./handlers";

const TOKEN = "AbCdEfGhIjKlMnOpQrStUv";
const KEY = "0b6f2c1e-8a3d-4f5b-9c7e-1d2e3f4a5b6c";

const catalog: CatalogView = {
  businessName: "Nena's Longganisa",
  resellerName: "Aling Rosa",
  deliveryDate: "2026-12-01",
  cutoff: "16:00:00",
  products: [{ id: 1, name: "Longganisa Hamonado", unit: "pack", price: 120 }],
  recentOrders: [],
};

const quote: QuoteView = {
  id: 12,
  number: 3,
  resellerId: 7,
  resellerName: "Aling Rosa",
  createdAt: "2026-11-27T02:00:00Z",
  deliveryDate: "2026-11-28",
  expiresOn: "2026-12-04",
  status: "sent",
  total: 1000,
  note: "Holiday price",
  acceptedOrderId: null,
  sentAt: "2026-11-27T02:05:00Z",
  acceptedAt: null,
  declinedAt: null,
  cancelledAt: null,
  lines: [{ productId: 1, productName: "Longganisa Hamonado", unit: "pack", quantity: 10, price: 100, total: 1000 }],
};

const summary: QuoteSummaryView = {
  id: 12,
  number: 3,
  resellerId: 7,
  resellerName: "Aling Rosa",
  createdAt: quote.createdAt,
  deliveryDate: quote.deliveryDate,
  expiresOn: quote.expiresOn,
  status: "sent",
  total: 1000,
};

const order = { id: 41, number: 9 } as OrderView;

type Route = () => { status: number; body?: unknown };
let routes: Record<string, Route>;
let calls: { key: string; body: unknown }[];

const fakeFetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
  const path = String(input).replace("http://localhost:8080/api", "");
  const key = `${init?.method ?? "GET"} ${path}`;
  calls.push({ key, body: init?.body ? JSON.parse(String(init.body)) : undefined });
  const route = routes[key];
  const { status, body } = route ? route() : { status: 404, body: { status: 404, detail: "Not found" } };
  return new Response(body === undefined ? null : JSON.stringify(body), { status });
}) as typeof fetch;

function form(fields: Record<string, string>): FormData {
  const data = new FormData();
  for (const [k, v] of Object.entries(fields)) data.append(k, v);
  return data;
}

const Q = `/order/${TOKEN}/quotes/12`;

beforeEach(() => {
  calls = [];
  routes = {
    [`GET /order/${TOKEN}`]: () => ({ status: 200, body: catalog }),
    [`GET /order/${TOKEN}/quotes`]: () => ({ status: 200, body: [summary] }),
    [`GET ${Q}`]: () => ({ status: 200, body: quote }),
  };
});

describe("price list", () => {
  test("lists the open quotes with a link to each", async () => {
    const page = await (await showPriceList(TOKEN, fakeFetch)).text();

    expect(page).toContain("Quotes for you");
    expect(page).toContain(`href="${Q}"`);
    expect(page).toContain("#Q3 · ₱1,000.00 · accept by Fri, 4 Dec");
  });

  test("still shows the price list when the quotes cannot be read", async () => {
    routes[`GET /order/${TOKEN}/quotes`] = () => ({ status: 500, body: { status: 500 } });
    const response = await showPriceList(TOKEN, fakeFetch);
    const page = await response.text();

    expect(response.status).toBe(200);
    expect(page).toContain("Place order");
    expect(page).not.toContain("Quotes for you");
  });
});

describe("quote page", () => {
  test("a sent quote shows its lines, the delivery an accept gives, accept and decline", async () => {
    const response = await showQuote(TOKEN, "12", fakeFetch);
    const page = await response.text();

    expect(response.status).toBe(200);
    expect(response.headers.get("cache-control")).toBe("private, max-age=0");
    expect(page).toContain("Nena&#39;s Longganisa");
    expect(page).toContain("Quote #Q3");
    expect(page).toContain("Accept by Fri, 4 Dec");
    expect(page).toContain("10 pack × ₱100.00");
    expect(page).toContain("Holiday price");
    // The catalog's date is what an order placed now gets; the quote's own date is stale.
    expect(page).toContain("for delivery on Tue, 1 Dec");
    expect(page).toMatch(/action="[^"]+\/quotes\/12\/accept"><input type="hidden" name="idempotencyKey" value="[0-9a-f-]{36}"/);
    expect(page).toContain('data-confirm="Decline quote #Q3?"');
  });

  test("an accepted quote links to its order and offers no buttons", async () => {
    routes[`GET ${Q}`] = () => ({ status: 200, body: { ...quote, status: "accepted", acceptedOrderId: 41 } });
    const page = await (await showQuote(TOKEN, "12", fakeFetch)).text();

    expect(page).toContain("You accepted this quote.");
    expect(page).toContain(`href="/order/${TOKEN}/orders/41"`);
    expect(page).not.toContain("Accept and place order");
    expect(page).not.toContain("Decline");
  });

  test("an expired quote says so", async () => {
    routes[`GET ${Q}`] = () => ({ status: 200, body: { ...quote, status: "expired" } });
    const page = await (await showQuote(TOKEN, "12", fakeFetch)).text();

    expect(page).toContain("This quote ended on Fri, 4 Dec.");
    expect(page).not.toContain("Accept and place order");
  });

  test("a quote that is not this reseller's, or a bad id, is the inactive page", async () => {
    routes[`GET ${Q}`] = () => ({ status: 404, body: { status: 404 } });
    for (const id of ["12", "abc", "0"]) {
      const response = await showQuote(TOKEN, id, fakeFetch);
      expect(response.status).toBe(404);
      const page = await response.text();
      expect(page).toContain("This link is no longer active.");
      expect(page).not.toContain("Nena");
    }
  });
});

describe("accepting", () => {
  test("posts the key and redirects to the order", async () => {
    routes[`POST ${Q}/accept`] = () => ({ status: 201, body: order });
    const response = await acceptQuote(TOKEN, "12", form({ idempotencyKey: KEY }), fakeFetch);

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toBe(`/order/${TOKEN}/orders/41`);
    expect(calls.at(-1)).toEqual({ key: `POST ${Q}/accept`, body: { idempotencyKey: KEY } });
  });

  test("an accept replayed by the API goes to the same order", async () => {
    routes[`POST ${Q}/accept`] = () => ({ status: 200, body: order });
    const response = await acceptQuote(TOKEN, "12", form({ idempotencyKey: KEY }), fakeFetch);

    expect(response.headers.get("location")).toBe(`/order/${TOKEN}/orders/41`);
  });

  test("a quote that can no longer be accepted shows as it is now", async () => {
    routes[`POST ${Q}/accept`] = () => ({ status: 409, body: { status: 409, detail: "Quote is now expired" } });
    routes[`GET ${Q}`] = () => ({ status: 200, body: { ...quote, status: "expired" } });
    const response = await acceptQuote(TOKEN, "12", form({ idempotencyKey: KEY }), fakeFetch);
    const page = await response.text();

    expect(response.status).toBe(409);
    expect(page).toContain("This quote can no longer be accepted.");
    expect(page).toContain("This quote ended on");
  });

  test("a missing key does not reach the API", async () => {
    const response = await acceptQuote(TOKEN, "12", form({}), fakeFetch);

    expect(response.status).toBe(422);
    expect(await response.text()).toContain("Please accept the quote again.");
    expect(calls.some((c) => c.key.startsWith("POST"))).toBe(false);
  });
});

describe("declining", () => {
  test("without the script it asks on a page first", async () => {
    const response = await declineQuote(TOKEN, "12", form({}), fakeFetch);
    const page = await response.text();

    expect(page).toContain("Decline quote #Q3?");
    expect(page).toContain('name="confirmed" value="yes"');
    expect(calls.some((c) => c.key.startsWith("POST"))).toBe(false);
  });

  test("confirmed, it declines and returns to the quote", async () => {
    routes[`POST ${Q}/decline`] = () => ({ status: 200, body: { ...quote, status: "declined" } });
    const response = await declineQuote(TOKEN, "12", form({ confirmed: "yes" }), fakeFetch);

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toBe(Q);
  });

  test("a quote the supplier withdrew meanwhile says it can no longer be declined", async () => {
    routes[`POST ${Q}/decline`] = () => ({ status: 409, body: { status: 409 } });
    const now = { ...quote, status: "cancelled" };
    routes[`GET ${Q}`] = () => ({ status: 200, body: now });
    const response = await declineQuote(TOKEN, "12", form({ confirmed: "yes" }), fakeFetch);
    const page = await response.text();

    expect(response.status).toBe(409);
    expect(page).toContain("This quote can no longer be declined.");
    expect(page).toContain("Your supplier withdrew this quote.");
  });
});
