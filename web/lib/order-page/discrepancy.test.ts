import { beforeEach, describe, expect, test } from "vitest";
import type { DiscrepancyView, OrderView } from "../api-types";
import { showOrder } from "./handlers";
import { approveDiscrepancy, declineDiscrepancy, fileDiscrepancy, showDiscrepancy } from "./discrepancy";

const TOKEN = "AbCdEfGhIjKlMnOpQrStUv";
const KEY = "0b6f2c1e-8a3d-4f5b-9c7e-1d2e3f4a5b6c";
const O = `/order/${TOKEN}/orders/41`;
const D = `${O}/discrepancy`;

const order: OrderView = {
  id: 41,
  number: 9,
  resellerId: 7,
  resellerName: "Aling Rosa",
  placedAt: "2026-11-26T02:00:00Z",
  deliveryDate: "2026-11-27",
  status: "delivered",
  total: 2800,
  paid: false,
  note: "",
  confirmedAt: null,
  deliveredAt: "2026-11-27T01:00:00Z",
  cancelledAt: null,
  paidAt: null,
  events: [],
  lines: [
    { productId: 1, productName: "Longganisa Hamonado", unit: "pack", quantity: 10, price: 120, total: 1200 },
    { productId: 3, productName: "Tocino", unit: "kg", quantity: 5, price: 320, total: 1600 },
  ],
};

const report: DiscrepancyView = {
  id: 5,
  orderId: 41,
  orderNumber: 9,
  filedBy: "reseller",
  filedAt: "2026-11-27T03:00:00Z",
  status: "open",
  note: "Two packs were crushed",
  resolutionNote: "",
  resolvedBy: null,
  resolvedAt: null,
  shortfallTotal: 240,
  lines: [
    { productId: 1, productName: "Longganisa Hamonado", unit: "pack", orderedQuantity: 10, receivedQuantity: 8, price: 120, shortfallTotal: 240 },
  ],
};

const byOwner: DiscrepancyView = { ...report, filedBy: "owner", note: "Short on the truck" };

type Route = () => { status: number; body?: unknown };
let routes: Record<string, Route>;
let calls: { key: string; body: unknown; contentType: string | null }[];

const fakeFetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
  const path = String(input).replace("http://localhost:8080/api", "");
  const key = `${init?.method ?? "GET"} ${path}`;
  const headers = new Headers(init?.headers);
  calls.push({ key, body: init?.body ? JSON.parse(String(init.body)) : undefined, contentType: headers.get("content-type") });
  const route = routes[key];
  const { status, body } = route ? route() : { status: 404, body: { status: 404, detail: "Not found" } };
  return new Response(body === undefined ? null : JSON.stringify(body), { status });
}) as typeof fetch;

function form(fields: Record<string, string>): FormData {
  const data = new FormData();
  for (const [k, v] of Object.entries(fields)) data.append(k, v);
  return data;
}

const posted = (key: string) => calls.filter((c) => c.key === key);
const noProblem = { status: 404, body: { status: 404, detail: "Not found" } };

beforeEach(() => {
  calls = [];
  routes = {
    [`GET ${O}`]: () => ({ status: 200, body: order }),
    [`GET ${D}`]: () => noProblem,
  };
});

describe("the delivered order page", () => {
  test("offers to report a problem when there is no report", async () => {
    const page = await (await showOrder(TOKEN, "41", fakeFetch)).text();

    expect(page).toContain(`<a href="${D}">Report a problem with this delivery</a>`);
  });

  test("shows the report's status with a link instead, once there is one", async () => {
    routes[`GET ${D}`] = () => ({ status: 200, body: report });
    const page = await (await showOrder(TOKEN, "41", fakeFetch)).text();

    expect(page).toContain(`<a href="${D}">View report</a>`);
    expect(page).toMatch(/class="badge open">Open</);
    expect(page).not.toContain("Report a problem with this delivery");
  });

  test("an order that is not delivered neither offers a report nor asks the API about one", async () => {
    routes[`GET ${O}`] = () => ({ status: 200, body: { ...order, status: "confirmed" } });
    const page = await (await showOrder(TOKEN, "41", fakeFetch)).text();

    expect(page).not.toContain("Report a problem");
    expect(posted(`GET ${D}`)).toHaveLength(0);
  });

  test("the order still shows when the report cannot be read", async () => {
    routes[`GET ${D}`] = () => ({ status: 500, body: { status: 500 } });
    const response = await showOrder(TOKEN, "41", fakeFetch);
    const page = await response.text();

    expect(response.status).toBe(200);
    expect(page).toContain("Order #9 placed");
    expect(page).not.toContain("Report a problem");
    expect(page).not.toContain("View report");
  });
});

describe("the report form", () => {
  test("one received box per line, starting at what was ordered, with a note and a key", async () => {
    const response = await showDiscrepancy(TOKEN, "41", fakeFetch);
    const page = await response.text();

    expect(response.status).toBe(200);
    expect(response.headers.get("cache-control")).toBe("private, max-age=0");
    expect(response.headers.get("referrer-policy")).toBe("no-referrer");
    expect(response.headers.get("x-robots-tag")).toBe("noindex");
    expect(response.headers.get("set-cookie")).toBeNull();
    expect(page).toContain("Order #9");
    expect(page).toContain(`method="post" action="${D}"`);
    expect(page).toMatch(/inputmode="numeric"[^>]*step="1"[^>]*id="r1"[^>]*name="r1"[^>]*value="10"/);
    expect(page).toMatch(/inputmode="decimal"[^>]*step="0.001"[^>]*id="r3"[^>]*name="r3"[^>]*value="5"/);
    expect(page).toContain('aria-label="Received quantity of Longganisa Hamonado"');
    expect(page).toContain('aria-label="Received quantity of Tocino"');
    expect(page).toContain("<textarea");
    expect(page).toMatch(/name="idempotencyKey" value="[0-9a-f-]{36}"/);
    expect(page).toContain("Send report");
  });

  test("an order that is not delivered shows no form and says why", async () => {
    routes[`GET ${O}`] = () => ({ status: 200, body: { ...order, status: "confirmed" } });
    const response = await showDiscrepancy(TOKEN, "41", fakeFetch);
    const page = await response.text();

    expect(response.status).toBe(409);
    expect(page).toContain("Only a delivered order can be reported.");
    expect(page).not.toContain("Send report");
  });

  test("an unknown order or link gets the inactive page, and a bad id never reaches the API", async () => {
    routes[`GET ${O}`] = () => noProblem;
    const missing = await showDiscrepancy(TOKEN, "41", fakeFetch);
    expect(missing.status).toBe(404);
    expect(await missing.text()).toContain("This link is no longer active.");

    calls = [];
    const bad = await showDiscrepancy(TOKEN, "4x", fakeFetch);
    expect(bad.status).toBe(404);
    expect(calls).toHaveLength(0);
  });

  test("an API failure shows the try-again page", async () => {
    routes[`GET ${O}`] = () => ({ status: 500, body: {} });
    const response = await showDiscrepancy(TOKEN, "41", fakeFetch);

    expect(response.status).toBe(503);
    expect(await response.text()).toContain("Please try again");
  });
});

describe("filing a report", () => {
  const fields = { idempotencyKey: KEY, r1: "8", r3: "5", note: "two were crushed" };

  test("sends only the lines that differ and goes to the report", async () => {
    routes[`POST ${D}`] = () => ({ status: 201, body: report });
    const response = await fileDiscrepancy(TOKEN, "41", form(fields), fakeFetch);

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toBe(D);
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(posted(`POST ${D}`)).toEqual([
      {
        key: `POST ${D}`,
        body: { lines: [{ productId: 1, receivedQuantity: 8 }], note: "two were crushed", idempotencyKey: KEY },
        contentType: "application/json",
      },
    ]);
  });

  test("nothing differing re-renders the form with a message and sends nothing", async () => {
    const response = await fileDiscrepancy(TOKEN, "41", form({ ...fields, r1: "10" }), fakeFetch);
    const page = await response.text();

    expect(response.status).toBe(422);
    expect(page).toContain("Change at least one received quantity to report a problem.");
    expect(page).toContain('role="alert"');
    expect(page).toContain("two were crushed");
    expect(posted(`POST ${D}`)).toHaveLength(0);
  });

  test("a bad quantity names its product, keeps what was typed and sends nothing", async () => {
    const response = await fileDiscrepancy(TOKEN, "41", form({ ...fields, r1: "1.5" }), fakeFetch);
    const page = await response.text();

    expect(response.status).toBe(422);
    expect(page).toContain('<a href="#r1">Longganisa Hamonado: Enter a whole number from 0 to 9999.</a>');
    expect(page).toMatch(/id="r1"[^>]*name="r1"[^>]*value="1.5"/);
    expect(posted(`POST ${D}`)).toHaveLength(0);
  });

  test("a missing key asks for the report again, with a fresh key", async () => {
    const response = await fileDiscrepancy(TOKEN, "41", form({ r1: "8", note: "" }), fakeFetch);
    const page = await response.text();

    expect(response.status).toBe(422);
    expect(page).toContain("Please report the problem again.");
    expect(page).toMatch(/name="idempotencyKey" value="[0-9a-f-]{36}"/);
    expect(posted(`POST ${D}`)).toHaveLength(0);
  });

  test("the API's field errors come back beside the product, with the typed values", async () => {
    routes[`POST ${D}`] = () => ({
      status: 422,
      body: { status: 422, errors: { "lines[0].receivedQuantity": "Received quantity matches what was ordered" } },
    });
    const response = await fileDiscrepancy(TOKEN, "41", form(fields), fakeFetch);
    const page = await response.text();

    expect(response.status).toBe(422);
    expect(page).toContain("Longganisa Hamonado: Received quantity matches what was ordered.");
    expect(page).toMatch(/id="r1"[^>]*name="r1"[^>]*value="8"/);
  });

  test("a report that already exists is shown, not an error", async () => {
    routes[`POST ${D}`] = () => ({ status: 409, body: { status: 409, detail: "This order already has a report" } });
    routes[`GET ${D}`] = () => ({ status: 200, body: byOwner });
    const response = await fileDiscrepancy(TOKEN, "41", form(fields), fakeFetch);

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toBe(D);
  });

  test("an order that is no longer delivered says so", async () => {
    routes[`POST ${D}`] = () => ({ status: 409, body: { status: 409, detail: "Order is now cancelled" } });
    routes[`GET ${O}`] = () => ({ status: 200, body: { ...order, status: "cancelled" } });
    const response = await fileDiscrepancy(TOKEN, "41", form(fields), fakeFetch);
    const page = await response.text();

    expect(response.status).toBe(409);
    expect(page).toContain("Only a delivered order can be reported.");
    expect(page).not.toContain("Send report");
  });

  test("a link that is no longer active gets the inactive page; an API failure gets try again", async () => {
    routes[`POST ${D}`] = () => noProblem;
    const gone = await fileDiscrepancy(TOKEN, "41", form(fields), fakeFetch);
    expect(gone.status).toBe(404);
    expect(await gone.text()).toContain("This link is no longer active.");

    routes[`POST ${D}`] = () => ({ status: 500, body: {} });
    const down = await fileDiscrepancy(TOKEN, "41", form(fields), fakeFetch);
    expect(down.status).toBe(503);
  });
});

describe("the report", () => {
  test("a report the reseller filed shows the lines, shortfall and note, and waits for the supplier", async () => {
    routes[`GET ${D}`] = () => ({ status: 200, body: report });
    const response = await showDiscrepancy(TOKEN, "41", fakeFetch);
    const page = await response.text();

    expect(response.status).toBe(200);
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(page).toContain("Order #9");
    expect(page).toMatch(/class="badge open">Open</);
    expect(page).toContain("Longganisa Hamonado");
    expect(page).toContain("8 of 10 pack");
    expect(page).toContain("₱240.00 short");
    expect(page).toContain("Two packs were crushed");
    expect(page).toContain("You reported this");
    expect(page).toContain("Waiting for your supplier");
    expect(page).not.toContain(`${D}/approve`);
    expect(page).not.toContain(`${D}/decline`);
    expect(page).not.toContain("Send report");
  });

  test("a report the supplier filed offers approve and decline", async () => {
    routes[`GET ${D}`] = () => ({ status: 200, body: byOwner });
    const page = await (await showDiscrepancy(TOKEN, "41", fakeFetch)).text();

    expect(page).toContain("Your supplier reported this");
    expect(page).toContain("Short on the truck");
    expect(page).toContain(`method="post" action="${D}/approve"`);
    expect(page).toContain(`method="post" action="${D}/decline"`);
    expect(page).toContain(">Approve</button>");
    expect(page).toContain(">Decline</button>");
  });

  test("a resolved report shows who answered and what they said, and no buttons", async () => {
    routes[`GET ${D}`] = () => ({
      status: 200,
      body: { ...byOwner, status: "approved", resolvedBy: "reseller", resolvedAt: "2026-11-27T05:00:00Z", resolutionNote: "Agreed, thank you" },
    });
    const page = await (await showDiscrepancy(TOKEN, "41", fakeFetch)).text();

    expect(page).toMatch(/class="badge approved">Approved</);
    expect(page).toContain("Agreed, thank you");
    expect(page).toContain("You approved this");
    expect(page).not.toContain(`${D}/approve`);

    routes[`GET ${D}`] = () => ({
      status: 200,
      body: { ...report, status: "declined", resolvedBy: "owner", resolvedAt: "2026-11-27T05:00:00Z", resolutionNote: "" },
    });
    const declined = await (await showDiscrepancy(TOKEN, "41", fakeFetch)).text();
    expect(declined).toMatch(/class="badge declined">Declined</);
    expect(declined).toContain("Your supplier declined this");
  });

  test("over-delivery reads as over, not as a negative", async () => {
    routes[`GET ${D}`] = () => ({
      status: 200,
      body: {
        ...report,
        shortfallTotal: -240,
        lines: [{ ...report.lines[0], receivedQuantity: 12, shortfallTotal: -240 }],
      },
    });
    const page = await (await showDiscrepancy(TOKEN, "41", fakeFetch)).text();

    expect(page).toContain("12 of 10 pack");
    expect(page).toContain("₱240.00 over");
    expect(page).not.toContain("-₱");
    expect(page).not.toContain("₱240.00 short");
  });

  test("the page stays under 50 KB with 50 lines", async () => {
    const many = Array.from({ length: 50 }, (_, i) => ({
      ...report.lines[0],
      productId: i + 1,
      productName: `Product ${i + 1} with a longer name`,
    }));
    routes[`GET ${D}`] = () => ({ status: 200, body: { ...report, lines: many, note: "n".repeat(500) } });
    const page = await (await showDiscrepancy(TOKEN, "41", fakeFetch)).text();

    expect(new TextEncoder().encode(page).length).toBeLessThan(50 * 1024);
  });
});

describe("approving and declining a report the supplier filed", () => {
  beforeEach(() => {
    routes[`GET ${D}`] = () => ({ status: 200, body: byOwner });
  });

  test("approve asks first, with an optional note, and answers nothing yet", async () => {
    const response = await approveDiscrepancy(TOKEN, "41", form({}), fakeFetch);
    const page = await response.text();

    expect(response.status).toBe(200);
    expect(page).toContain("Approve this report?");
    expect(page).toContain(`action="${D}/approve"`);
    expect(page).toContain('name="confirmed" value="yes"');
    expect(page).toContain("<textarea");
    expect(page).toContain(`href="${D}"`);
    expect(posted(`POST ${D}/approve`)).toHaveLength(0);
  });

  test("decline asks first the same way", async () => {
    const response = await declineDiscrepancy(TOKEN, "41", form({}), fakeFetch);
    const page = await response.text();

    expect(response.status).toBe(200);
    expect(page).toContain("Decline this report?");
    expect(page).toContain(`action="${D}/decline"`);
    expect(posted(`POST ${D}/decline`)).toHaveLength(0);
  });

  test("confirmed, approve sends the note as JSON and goes to the report", async () => {
    routes[`POST ${D}/approve`] = () => ({ status: 200, body: { ...byOwner, status: "approved" } });
    const response = await approveDiscrepancy(TOKEN, "41", form({ confirmed: "yes", note: "  thank you " }), fakeFetch);

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toBe(D);
    expect(posted(`POST ${D}/approve`)).toEqual([
      { key: `POST ${D}/approve`, body: { note: "thank you" }, contentType: "application/json" },
    ]);
  });

  test("confirmed with no note still sends a JSON body", async () => {
    routes[`POST ${D}/decline`] = () => ({ status: 200, body: { ...byOwner, status: "declined" } });
    const response = await declineDiscrepancy(TOKEN, "41", form({ confirmed: "yes", note: "" }), fakeFetch);

    expect(response.status).toBe(303);
    expect(posted(`POST ${D}/decline`)).toEqual([
      { key: `POST ${D}/decline`, body: {}, contentType: "application/json" },
    ]);
  });

  test("a report the reseller filed cannot be answered by the reseller, and the API is not asked", async () => {
    routes[`GET ${D}`] = () => ({ status: 200, body: report });
    const response = await approveDiscrepancy(TOKEN, "41", form({ confirmed: "yes" }), fakeFetch);

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toBe(D);
    expect(posted(`POST ${D}/approve`)).toHaveLength(0);
  });

  test("an answer that was already given shows the report as it is now", async () => {
    routes[`POST ${D}/approve`] = () => ({ status: 409, body: { status: 409, detail: "This report is now declined" } });
    routes[`GET ${D}`] = (() => {
      let reads = 0;
      return () => ({
        status: 200,
        body: ++reads === 1 ? byOwner : { ...byOwner, status: "declined", resolvedBy: "reseller", resolvedAt: "2026-11-27T05:00:00Z" },
      });
    })();
    const response = await approveDiscrepancy(TOKEN, "41", form({ confirmed: "yes" }), fakeFetch);
    const page = await response.text();

    expect(response.status).toBe(409);
    expect(page).toContain("This report can no longer be answered.");
    expect(page).toMatch(/class="badge declined">Declined</);
  });

  test("no report is back to the report page; a dead link is the inactive page", async () => {
    routes[`GET ${D}`] = () => noProblem;
    const none = await approveDiscrepancy(TOKEN, "41", form({ confirmed: "yes" }), fakeFetch);
    expect(none.status).toBe(303);
    expect(none.headers.get("location")).toBe(D);

    const bad = await declineDiscrepancy(TOKEN, "x", form({ confirmed: "yes" }), fakeFetch);
    expect(bad.status).toBe(404);
  });

  test("an API failure gets the try-again page", async () => {
    routes[`POST ${D}/approve`] = () => ({ status: 500, body: {} });
    const response = await approveDiscrepancy(TOKEN, "41", form({ confirmed: "yes" }), fakeFetch);

    expect(response.status).toBe(503);
  });
});

describe("markup in a note (threat C14)", () => {
  const script = "<script>alert(1)</script>";

  test("is escaped on the report page, in every field the other party wrote", async () => {
    routes[`GET ${D}`] = () => ({
      status: 200,
      body: {
        ...byOwner,
        note: script,
        status: "declined",
        resolvedBy: "reseller",
        resolvedAt: "2026-11-27T05:00:00Z",
        resolutionNote: `"><img src=x onerror=alert(2)>`,
        lines: [{ ...report.lines[0], productName: script }],
      },
    });
    const page = await (await showDiscrepancy(TOKEN, "41", fakeFetch)).text();

    expect(page).not.toContain("<script>alert");
    expect(page).not.toContain("<img");
    expect(page).toContain("&lt;script&gt;alert(1)&lt;/script&gt;");
    expect(page).toContain("&quot;&gt;&lt;img src=x onerror=alert(2)&gt;");
  });

  test("is escaped when the form is shown again with what the reseller typed", async () => {
    const response = await fileDiscrepancy(
      TOKEN,
      "41",
      form({ idempotencyKey: KEY, r1: "10", note: script }),
      fakeFetch,
    );
    const page = await response.text();

    expect(response.status).toBe(422);
    expect(page).not.toContain("<script>alert");
    expect(page).toContain("&lt;script&gt;alert(1)&lt;/script&gt;");
  });

  test("is escaped on the confirmation page's note box and nowhere else is it echoed raw", async () => {
    routes[`GET ${D}`] = () => ({ status: 200, body: { ...byOwner, note: script } });
    const page = await (await approveDiscrepancy(TOKEN, "41", form({ note: script }), fakeFetch)).text();

    expect(page).not.toContain("<script>alert");
  });
});
