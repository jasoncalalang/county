import { beforeEach, describe, expect, test } from "vitest";
import type { CatalogView, OrderView } from "../api-types";
import { cancelOrder, placeOrder, showOrder, showPriceList } from "./handlers";

const TOKEN = "AbCdEfGhIjKlMnOpQrStUv";

const catalog: CatalogView = {
  businessName: "Nena's Longganisa",
  resellerName: "Aling Rosa",
  deliveryDate: "2026-11-27",
  cutoff: "16:00:00",
  products: [
    { id: 1, name: "Longganisa Hamonado", unit: "pack", price: 120 },
    { id: 3, name: "Tocino", unit: "kg", price: 320 },
  ],
  recentOrders: [],
};

const order: OrderView = {
  id: 41,
  number: 1,
  resellerId: 7,
  resellerName: "Aling Rosa",
  placedAt: "2026-11-26T02:00:00Z",
  deliveryDate: "2026-11-27",
  status: "placed",
  total: 2800,
  paid: false,
  note: "before 7am",
  confirmedAt: null,
  deliveredAt: null,
  cancelledAt: null,
  paidAt: null,
  events: [],
  lines: [
    { productId: 1, productName: "Longganisa Hamonado", unit: "pack", quantity: 10, price: 120, total: 1200 },
    { productId: 3, productName: "Tocino", unit: "kg", quantity: 5, price: 320, total: 1600 },
  ],
};

type Route = (init: RequestInit | undefined) => { status: number; body?: unknown };
let routes: Record<string, Route>;
let calls: { key: string; body: unknown }[];

const fakeFetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
  const path = String(input).replace("http://localhost:8080/api", "");
  const key = `${init?.method ?? "GET"} ${path}`;
  calls.push({ key, body: init?.body ? JSON.parse(String(init.body)) : undefined });
  const route = routes[key];
  const { status, body } = route ? route(init) : { status: 404, body: { status: 404, detail: "Not found" } };
  return new Response(body === undefined ? null : JSON.stringify(body), { status });
}) as typeof fetch;

function form(fields: Record<string, string>): FormData {
  const data = new FormData();
  for (const [k, v] of Object.entries(fields)) data.append(k, v);
  return data;
}

const KEY = "0b6f2c1e-8a3d-4f5b-9c7e-1d2e3f4a5b6c";

beforeEach(() => {
  calls = [];
  routes = {
    [`GET /order/${TOKEN}`]: () => ({ status: 200, body: catalog }),
    [`GET /order/${TOKEN}/orders/41`]: () => ({ status: 200, body: order }),
  };
});

describe("price list", () => {
  test("shows business, reseller, delivery date and one quantity field per product", async () => {
    const response = await showPriceList(TOKEN, fakeFetch);
    const page = await response.text();

    expect(response.status).toBe(200);
    expect(response.headers.get("cache-control")).toBe("private, max-age=0");
    expect(response.headers.get("set-cookie")).toBeNull();
    expect(page).toContain("Nena&#39;s Longganisa");
    expect(page).toContain("Ordering as Aling Rosa");
    expect(page).toContain("Delivery on Fri, 27 Nov");
    expect(page).toContain('name="q1"');
    expect(page).toContain('name="q3"');
    expect(page).toMatch(/name="idempotencyKey" value="[0-9a-f-]{36}"/);
    expect(page).not.toContain("autocomplete=\"off\"");
  });

  test("a kg product takes decimals and a pack takes whole numbers", async () => {
    const page = await (await showPriceList(TOKEN, fakeFetch)).text();

    expect(page).toMatch(/inputmode="numeric"[^>]*step="1"[^>]*id="q1"/);
    expect(page).toMatch(/inputmode="decimal"[^>]*step="0.001"[^>]*id="q3"/);
  });

  test("stays within the page budget: under 50 KB with a 2 KB script and 8 KB of CSS", async () => {
    const many = { ...catalog, products: Array.from({ length: 50 }, (_, i) => ({ id: i + 1, name: `Product ${i + 1} with a longer name`, unit: "pack" as const, price: 123.45 })) };
    routes[`GET /order/${TOKEN}`] = () => ({ status: 200, body: { ...many, recentOrders: Array.from({ length: 10 }, (_, i) => ({ id: i + 1, number: i + 1, deliveryDate: "2026-11-27", total: 2160, status: "placed", placedAt: "2026-11-26T02:00:00Z" })) } });
    const page = await (await showPriceList(TOKEN, fakeFetch)).text();
    const { ORDER_PAGE_SCRIPT } = await import("./script");
    const { ORDER_PAGE_CSS } = await import("./styles");

    expect(new TextEncoder().encode(page).length).toBeLessThan(50 * 1024);
    expect(new TextEncoder().encode(ORDER_PAGE_SCRIPT).length).toBeLessThan(2 * 1024);
    expect(new TextEncoder().encode(ORDER_PAGE_CSS).length).toBeLessThan(8 * 1024);
  });

  test("an unknown or blocked token shows only the inactive message with 404", async () => {
    const response = await showPriceList("nope", fakeFetch);
    const page = await response.text();

    expect(response.status).toBe(404);
    expect(page).toContain("This link is no longer active.");
    expect(page).not.toContain("Nena");
  });

  test("an empty price list says to contact the supplier instead of the form", async () => {
    routes[`GET /order/${TOKEN}`] = () => ({ status: 200, body: { ...catalog, products: [] } });
    const page = await (await showPriceList(TOKEN, fakeFetch)).text();

    expect(page).toContain("No products available yet. Please contact your supplier.");
    expect(page).not.toContain("Place order");
  });

  test("escapes names from the API", async () => {
    routes[`GET /order/${TOKEN}`] = () => ({ status: 200, body: { ...catalog, resellerName: "<script>x</script>" } });
    const page = await (await showPriceList(TOKEN, fakeFetch)).text();

    expect(page).not.toContain("<script>x</script>");
    expect(page).toContain("&lt;script&gt;x&lt;/script&gt;");
  });
});

describe("placing an order", () => {
  test("posts the non-zero lines and redirects to the confirmation", async () => {
    routes[`POST /order/${TOKEN}/orders`] = () => ({ status: 201, body: order });
    const response = await placeOrder(TOKEN, form({ idempotencyKey: KEY, q1: "10", q3: "5", note: "before 7am" }), fakeFetch);

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toBe(`/order/${TOKEN}/orders/41`);
    expect(calls.at(-1)?.body).toEqual({
      lines: [
        { productId: 1, quantity: 10 },
        { productId: 3, quantity: 5 },
      ],
      note: "before 7am",
      idempotencyKey: KEY,
    });
  });

  test("sends a fractional quantity for a kg product", async () => {
    routes[`POST /order/${TOKEN}/orders`] = () => ({ status: 201, body: order });
    const response = await placeOrder(TOKEN, form({ idempotencyKey: KEY, q1: "10", q3: "1.5" }), fakeFetch);

    expect(response.status).toBe(303);
    expect(calls.at(-1)?.body).toEqual({
      lines: [
        { productId: 1, quantity: 10 },
        { productId: 3, quantity: 1.5 },
      ],
      idempotencyKey: KEY,
    });
  });

  test("a kg quantity with four decimals marks its row and is not sent", async () => {
    const response = await placeOrder(TOKEN, form({ idempotencyKey: KEY, q1: "1", q3: "1.2345" }), fakeFetch);
    const page = await response.text();

    expect(response.status).toBe(422);
    expect(page).toContain('<a href="#q3">Enter a number from 0 to 9999, with up to 3 decimals.</a>');
    expect(page).toContain('class="row bad" for="q3"');
    expect(page).toContain('value="1.2345"');
    expect(calls.some((c) => c.key.startsWith("POST"))).toBe(false);
  });

  /** Guard: passes before the change too, because the typed value was already escaped. */
  test("markup typed as a quantity comes back escaped", async () => {
    const page = await (await placeOrder(TOKEN, form({ idempotencyKey: KEY, q3: '"><script>alert(1)</script>' }), fakeFetch)).text();

    expect(page).not.toContain("<script>alert(1)");
    expect(page).toContain("&lt;script&gt;alert(1)&lt;/script&gt;");
  });

  test("a replayed submission (200) lands on the same confirmation", async () => {
    routes[`POST /order/${TOKEN}/orders`] = () => ({ status: 200, body: order });
    const response = await placeOrder(TOKEN, form({ idempotencyKey: KEY, q1: "10", q3: "5" }), fakeFetch);

    expect(response.headers.get("location")).toBe(`/order/${TOKEN}/orders/41`);
  });

  test("all zero re-renders with the error and keeps the note", async () => {
    const response = await placeOrder(TOKEN, form({ idempotencyKey: KEY, q1: "0", q3: "0", note: "hello" }), fakeFetch);
    const page = await response.text();

    expect(response.status).toBe(422);
    expect(page).toContain("Add at least one product.");
    expect(page).toContain(">hello</textarea>");
    expect(calls.some((c) => c.key.startsWith("POST"))).toBe(false);
  });

  test("a bad quantity marks its row and keeps what was typed", async () => {
    const page = await (await placeOrder(TOKEN, form({ idempotencyKey: KEY, q1: "2.5", q3: "4" }), fakeFetch)).text();

    expect(page).toContain('<a href="#q1">Enter a whole number from 0 to 9999.</a>');
    expect(page).toContain('class="row bad" for="q1"');
    expect(page).toContain('value="2.5"');
    expect(page).toContain('id="q3" name="q3" value="4"');
  });

  test("a product deactivated since render re-renders with its name and keeps quantities", async () => {
    routes[`POST /order/${TOKEN}/orders`] = () => ({
      status: 422,
      body: { status: 422, detail: "Check the submitted fields", errors: { "lines[1].productId": "Tocino is no longer available" } },
    });
    routes[`GET /order/${TOKEN}`] = () => ({ status: 200, body: { ...catalog, products: [catalog.products[0]] } });
    const page = await (await placeOrder(TOKEN, form({ idempotencyKey: KEY, q1: "10", q3: "5" }), fakeFetch)).text();

    expect(page).toContain("<p>Tocino is no longer available.</p>");
    expect(page).not.toContain("Check the submitted fields");
    expect(page).toContain('id="q1" name="q1" value="10"');
  });

  test("a field error on a row still listed links to that row", async () => {
    routes[`POST /order/${TOKEN}/orders`] = () => ({
      status: 422,
      body: { status: 422, detail: "Check the submitted fields", errors: { "lines[0].quantity": "must be less than or equal to 9999" } },
    });
    const page = await (await placeOrder(TOKEN, form({ idempotencyKey: KEY, q1: "10" }), fakeFetch)).text();

    expect(page).toContain('<a href="#q1">must be less than or equal to 9999.</a>');
    expect(page).toContain('class="row bad" for="q1"');
  });

  test("a token blocked between render and submit shows the inactive page", async () => {
    const response = await placeOrder("blocked", form({ idempotencyKey: KEY, q1: "1" }), fakeFetch);

    expect(response.status).toBe(404);
    expect(await response.text()).toContain("This link is no longer active.");
  });
});

describe("confirmation and cancel", () => {
  test("confirmation shows number, lines, total, note and cancel while placed", async () => {
    const page = await (await showOrder(TOKEN, "41", fakeFetch)).text();

    expect(page).toContain("Order #1 placed");
    expect(page).toContain("₱2,800.00");
    expect(page).toContain("before 7am");
    expect(page).toContain("You can cancel it until then.");
    expect(page).toContain(`action="/order/${TOKEN}/orders/41/cancel"`);
  });

  test("a confirmed order has no cancel", async () => {
    routes[`GET /order/${TOKEN}/orders/41`] = () => ({ status: 200, body: { ...order, status: "confirmed" } });
    const page = await (await showOrder(TOKEN, "41", fakeFetch)).text();

    expect(page).not.toContain("/cancel");
    expect(page).toContain("Confirmed");
  });

  test("a non-numeric order id is a 404", async () => {
    expect((await showOrder(TOKEN, "abc", fakeFetch)).status).toBe(404);
  });

  test("cancel without the script asks on a page first", async () => {
    const response = await cancelOrder(TOKEN, "41", form({ back: "order" }), fakeFetch);
    const page = await response.text();

    expect(response.status).toBe(200);
    expect(page).toContain("Cancel order #1?");
    expect(page).toContain('name="confirmed" value="yes"');
    expect(calls.some((c) => c.key.startsWith("POST"))).toBe(false);
  });

  test("confirmed cancel posts and goes back where it came from", async () => {
    routes[`POST /order/${TOKEN}/orders/41/cancel`] = () => ({ status: 200, body: { ...order, status: "cancelled" } });

    const fromList = await cancelOrder(TOKEN, "41", form({ back: "list", confirmed: "yes" }), fakeFetch);
    expect(fromList.headers.get("location")).toBe(`/order/${TOKEN}`);

    const fromOrder = await cancelOrder(TOKEN, "41", form({ back: "order", confirmed: "yes" }), fakeFetch);
    expect(fromOrder.headers.get("location")).toBe(`/order/${TOKEN}/orders/41`);
  });

  test("cancelling an order the supplier confirmed says so", async () => {
    routes[`POST /order/${TOKEN}/orders/41/cancel`] = () => ({ status: 409, body: { status: 409, detail: "Order is now confirmed" } });
    routes[`GET /order/${TOKEN}/orders/41`] = () => ({ status: 200, body: { ...order, status: "confirmed" } });
    const response = await cancelOrder(TOKEN, "41", form({ back: "order", confirmed: "yes" }), fakeFetch);

    expect(response.status).toBe(409);
    expect(await response.text()).toContain("Order #1 is confirmed. Contact your supplier to change it.");
  });
});
