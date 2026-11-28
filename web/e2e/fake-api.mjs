// An in-memory stand-in for the county API, for the end-to-end tests only. It follows
// api/contract/openapi.yaml closely enough to drive every screen; it is not the API's rules.
// Owner calls need any bearer token: the dashboard's Google sign-in cannot run in a test.
import { randomBytes } from "node:crypto";
import { createServer } from "node:http";

const port = Number(process.env.FAKE_API_PORT ?? 3199);
let db;

function reset() {
  db = { tenant: null, products: [], resellers: [], orders: [], quotes: [], nextId: 1, nextNumber: 1, nextQuote: 1, keys: new Map() };
}
reset();

const now = () => new Date().toISOString();
const token = () => randomBytes(16).toString("base64url");

/** Tomorrow in Manila, then the next configured delivery day. Good enough for a fake. */
function deliveryDate(days = db.tenant.deliveryDays) {
  const manila = new Date(Date.now() + 8 * 3600_000);
  for (let i = 1; i <= 8; i += 1) {
    const d = new Date(Date.UTC(manila.getUTCFullYear(), manila.getUTCMonth(), manila.getUTCDate() + i));
    const iso = d.getUTCDay() === 0 ? 7 : d.getUTCDay();
    if (days.includes(iso)) return d.toISOString().slice(0, 10);
  }
}

function send(res, status, body) {
  res.writeHead(status, { "content-type": status >= 400 ? "application/problem+json" : "application/json" });
  res.end(body === undefined ? "" : JSON.stringify(body));
}
const problem = (res, status, detail, errors = {}) => send(res, status, { type: "about:blank", status, detail, errors });
const field = (res, path, message) => problem(res, 422, "Check the submitted fields", { [path]: message });
const OWNER_NAME = "E2E";

/** Price times quantity in centavos, rounded half up, from integers so floats cannot drift. */
const lineTotal = (price, quantity) => Math.floor((Math.round(price * 100) * Math.round(quantity * 1000) + 500) / 1000) / 100;
const sumTotals = (lines) => lines.reduce((cents, l) => cents + Math.round(l.total * 100), 0) / 100;
/** Up to three decimals, above 0 and at most 9999, whole unless the product is sold by the kg. */
function quantityProblem(product, quantity) {
  if (typeof quantity !== "number" || !(quantity > 0) || quantity > 9999) return "Enter a quantity from 0.001 to 9999";
  if (Math.abs(quantity * 1000 - Math.round(quantity * 1000)) > 1e-6) return "Use up to three decimals";
  if (product.unit !== "kg" && !Number.isInteger(quantity)) return `${product.name} is sold by the ${product.unit}, so enter a whole number`;
  if (lineTotal(product.price, quantity) < 0.01) return "A line must come to at least 0.01";
  return null;
}
const resellerName = (o) => db.resellers.find((r) => r.id === o.resellerId)?.name;

/**
 * The history as the viewer sees it: the owner sees the owner's display name (the one /me
 * returns); a reseller's link sees the business name instead, as the API does.
 */
function events(o, viewer) {
  return (o.events ?? []).map(({ actor, ...event }) => ({
    ...event,
    actorType: actor,
    actorName: actor === "reseller" ? resellerName(o) : viewer === "owner" ? OWNER_NAME : db.tenant.name,
  }));
}
function orderView(o, viewer = "owner") {
  return { ...o, resellerName: resellerName(o), lines: o.lines.map((l) => ({ ...l })), events: events(o, viewer) };
}
function summary(o) {
  const { id, number, resellerId, placedAt, deliveryDate, status, total, paid, note } = o;
  const items = o.lines.map(({ productName, unit, quantity }) => ({ productName, unit, quantity }));
  return { id, number, resellerId, resellerName: resellerName(o), placedAt, deliveryDate, status, total, paid, note, items };
}

function transition(o, target, actor, paid) {
  const allowed = {
    placed: actor === "owner" ? ["confirmed", "cancelled"] : ["cancelled"],
    confirmed: actor === "owner" ? ["delivered", "cancelled"] : [],
  };
  const from = o.status;
  const record = () => (o.events ??= []).push({ at: now(), fromStatus: from, toStatus: o.status, paid: o.paid, actor });
  if (target === "paid") {
    if (o.status !== "delivered" || o.paid) return false;
    o.paid = true;
    o.paidAt = now();
    record();
    return true;
  }
  if (!(allowed[o.status] ?? []).includes(target)) return false;
  o.status = target;
  o[`${target}At`] = now();
  if (target === "delivered") {
    o.paid = paid;
    o.paidAt = paid ? now() : null;
  }
  record();
  return true;
}

const manilaToday = () => new Date(Date.now() + 8 * 3600_000).toISOString().slice(0, 10);
/** Expiry is read, not stored: a sent quote past its last day is expired. */
const quoteStatus = (q) => (q.status === "sent" && manilaToday() > q.expiresOn ? "expired" : q.status);
function quoteSummary(q) {
  const { id, number, resellerId, createdAt, deliveryDate, expiresOn, total } = q;
  return { id, number, resellerId, resellerName: resellerName(q), createdAt, deliveryDate, expiresOn, status: quoteStatus(q), total };
}
const quoteView = (q) => ({ ...q, ...quoteSummary(q), lines: q.lines.map((l) => ({ ...l })) });

async function body(req) {
  let text = "";
  for await (const chunk of req) text += chunk;
  return text ? JSON.parse(text) : undefined;
}

createServer(async (req, res) => {
  const url = new URL(req.url, "http://fake");
  const path = url.pathname.replace(/^\/api/, "");
  const m = (re) => re.exec(path);
  let x;
  try {
    if (req.method === "POST" && path === "/__reset") return reset(), send(res, 204);

    // Reseller (token) quote routes
    if ((x = m(/^\/order\/([^/]+)\/quotes(?:\/(\d+)(?:\/(accept|decline))?)?$/))) {
      const reseller = db.resellers.find((r) => r.token === x[1] && r.status !== "blocked");
      if (!reseller) return problem(res, 404, "Not found");
      if (!x[2]) {
        const open = db.quotes.filter((q) => q.resellerId === reseller.id && quoteStatus(q) === "sent");
        return send(res, 200, open.reverse().map(quoteSummary));
      }
      const q = db.quotes.find((y) => y.id === Number(x[2]) && y.resellerId === reseller.id && y.sentAt);
      if (!q) return problem(res, 404, "Not found");
      if (!x[3] && req.method === "GET") return send(res, 200, quoteView(q));
      if (x[3] === "decline") {
        if (quoteStatus(q) !== "sent") return problem(res, 409, `Quote is now ${quoteStatus(q)}`);
        Object.assign(q, { status: "declined", declinedAt: now() });
        return send(res, 200, quoteView(q));
      }
      const input = await body(req);
      if (q.acceptedOrderId) return send(res, 200, orderView(db.orders.find((o) => o.id === q.acceptedOrderId), "reseller"));
      if (quoteStatus(q) !== "sent") return problem(res, 409, `Quote is now ${quoteStatus(q)}`);
      const order = {
        id: db.nextId++,
        number: db.nextNumber++,
        resellerId: reseller.id,
        placedAt: now(),
        deliveryDate: deliveryDate(),
        status: "placed",
        total: q.total,
        paid: false,
        note: "",
        confirmedAt: null,
        deliveredAt: null,
        cancelledAt: null,
        paidAt: null,
        lines: q.lines.map((l) => ({ ...l })),
      };
      db.orders.push(order);
      db.keys.set(input.idempotencyKey, order);
      Object.assign(q, { status: "accepted", acceptedAt: now(), acceptedOrderId: order.id });
      reseller.status = "active";
      reseller.lastOrderAt = order.placedAt;
      return send(res, 201, orderView(order, "reseller"));
    }

    // Reseller (token) routes
    if ((x = m(/^\/order\/([^/]+)(\/orders(?:\/(\d+)(\/cancel)?)?)?$/))) {
      const reseller = db.resellers.find((r) => r.token === x[1] && r.status !== "blocked");
      if (!reseller) return problem(res, 404, "Not found");
      if (!x[2] && req.method === "GET") {
        return send(res, 200, {
          businessName: db.tenant.name,
          resellerName: reseller.name,
          deliveryDate: deliveryDate(),
          cutoff: db.tenant.cutoff,
          products: db.products.filter((p) => p.active).map(({ id, name, unit, price }) => ({ id, name, unit, price })),
          recentOrders: db.orders
            .filter((o) => o.resellerId === reseller.id)
            .reverse()
            .slice(0, 10)
            .map(({ id, number, deliveryDate, total, status, placedAt }) => ({ id, number, deliveryDate, total, status, placedAt })),
        });
      }
      if (x[2] === "/orders" && req.method === "POST") {
        const input = await body(req);
        const seen = db.keys.get(input.idempotencyKey);
        if (seen) return send(res, 200, orderView(seen, "reseller"));
        const lines = [];
        for (const [i, line] of input.lines.entries()) {
          const p = db.products.find((q) => q.id === line.productId);
          if (!p) return problem(res, 404, "Not found");
          if (!p.active) return field(res, `lines[${i}].productId`, `${p.name} is no longer available`);
          const bad = quantityProblem(p, line.quantity);
          if (bad) return field(res, `lines[${i}].quantity`, bad);
          lines.push({ productId: p.id, productName: p.name, unit: p.unit, quantity: line.quantity, price: p.price, total: lineTotal(p.price, line.quantity) });
        }
        const order = {
          id: db.nextId++,
          number: db.nextNumber++,
          resellerId: reseller.id,
          placedAt: now(),
          deliveryDate: deliveryDate(),
          status: "placed",
          total: sumTotals(lines),
          paid: false,
          note: input.note ?? "",
          confirmedAt: null,
          deliveredAt: null,
          cancelledAt: null,
          paidAt: null,
          lines,
        };
        db.orders.push(order);
        db.keys.set(input.idempotencyKey, order);
        reseller.status = "active";
        reseller.lastOrderAt = order.placedAt;
        return send(res, 201, orderView(order, "reseller"));
      }
      const order = db.orders.find((o) => o.id === Number(x[3]) && o.resellerId === reseller.id);
      if (!order) return problem(res, 404, "Not found");
      if (!x[4] && req.method === "GET") return send(res, 200, orderView(order, "reseller"));
      if (x[4] && req.method === "POST") {
        if (!transition(order, "cancelled", "reseller")) return problem(res, 409, `Order is now ${order.status}`);
        return send(res, 200, orderView(order, "reseller"));
      }
    }

    // Owner routes
    if (!/^Bearer \S+/.test(req.headers.authorization ?? "")) return problem(res, 401, "Sign in required");
    if (path === "/me") return send(res, 200, { subject: "e2e", user: { id: 1, displayName: "E2E", email: null }, tenant: db.tenant });
    if (path === "/tenants" && req.method === "POST") {
      if (db.tenant) return problem(res, 409, "A record with those details already exists");
      const input = await body(req);
      db.tenant = { id: 1, name: input.name, cutoff: `${input.cutoff.slice(0, 5)}:00`, deliveryDays: input.deliveryDays };
      return send(res, 201, db.tenant);
    }
    if (path === "/settings/preview") {
      const cutoff = url.searchParams.get("cutoff") ?? "";
      if (!/^\d{2}:\d{2}/.test(cutoff)) return field(res, "cutoff", "Enter a time such as 16:00");
      const days = url.searchParams.getAll("deliveryDays").map(Number);
      if (!days.length) return field(res, "deliveryDays", "Choose at least one delivery day");
      return send(res, 200, { deliveryDate: deliveryDate(days), timeZone: "Asia/Manila" });
    }
    if (!db.tenant) return problem(res, 404, "Not found");
    if (path === "/settings") {
      if (req.method === "PUT") {
        const input = await body(req);
        Object.assign(db.tenant, { name: input.name, cutoff: `${input.cutoff.slice(0, 5)}:00`, deliveryDays: input.deliveryDays });
      }
      return send(res, 200, db.tenant);
    }
    if (path === "/products") {
      if (req.method === "POST") {
        const input = await body(req);
        const p = { id: db.nextId++, name: input.name, unit: input.unit, price: input.price, active: input.active ?? true };
        db.products.push(p);
        return send(res, 201, p);
      }
      return send(res, 200, db.products);
    }
    if ((x = m(/^\/products\/(\d+)$/)) && req.method === "PUT") {
      const p = db.products.find((q) => q.id === Number(x[1]));
      if (!p) return problem(res, 404, "Not found");
      Object.assign(p, await body(req));
      return send(res, 200, p);
    }
    if (path === "/resellers") {
      if (req.method === "POST") {
        const input = await body(req);
        const digits = input.mobile.replace(/[\s-]/g, "").replace(/^\+?63|^0/, "");
        if (!/^9\d{9}$/.test(digits)) return field(res, "mobile", "Enter a Philippine mobile number");
        const mobile = `+63${digits}`;
        if (db.resellers.some((r) => r.mobile === mobile)) return problem(res, 409, "A record with those details already exists");
        const t = token();
        const r = { id: db.nextId++, name: input.name, mobile, status: "invited", token: t, orderLink: `${process.env.PUBLIC_URL}/order/${t}`, lastOrderAt: null };
        db.resellers.push(r);
        return send(res, 201, r);
      }
      return send(res, 200, db.resellers);
    }
    if ((x = m(/^\/resellers\/(\d+)\/(block|unblock|reissue-link)$/))) {
      const r = db.resellers.find((q) => q.id === Number(x[1]));
      if (!r) return problem(res, 404, "Not found");
      if (x[2] === "block") r.status = "blocked";
      if (x[2] === "unblock") r.status = r.lastOrderAt ? "active" : "invited";
      if (x[2] === "reissue-link") {
        r.token = token();
        r.orderLink = `${process.env.PUBLIC_URL}/order/${r.token}`;
      }
      return send(res, 200, r);
    }
    if (path === "/orders") {
      const date = url.searchParams.get("date");
      const status = url.searchParams.get("status");
      return send(
        res,
        200,
        db.orders.filter((o) => (!date || o.deliveryDate === date) && (!status || o.status === status)).map(summary),
      );
    }
    if ((x = m(/^\/orders\/(\d+)(?:\/(confirm|cancel|deliver|mark-paid))?$/))) {
      const o = db.orders.find((q) => q.id === Number(x[1]));
      if (!o) return problem(res, 404, "Not found");
      if (!x[2]) return send(res, 200, orderView(o));
      const target = { confirm: "confirmed", cancel: "cancelled", deliver: "delivered", "mark-paid": "paid" }[x[2]];
      const input = x[2] === "deliver" ? await body(req) : undefined;
      if (!transition(o, target, "owner", input?.paid)) return problem(res, 409, `Order is now ${o.status}`);
      return send(res, 200, orderView(o));
    }
    if (path === "/quotes" && req.method === "POST") {
      const input = await body(req);
      const r = db.resellers.find((y) => y.id === input.resellerId);
      if (!r) return problem(res, 404, "Not found");
      if (r.status === "blocked") return field(res, "resellerId", "This reseller is blocked");
      if (!input.expiresOn || input.expiresOn < manilaToday()) return field(res, "expiresOn", "Choose today or a later day");
      const lines = [];
      for (const [i, line] of input.lines.entries()) {
        const p = db.products.find((y) => y.id === line.productId);
        if (!p) return problem(res, 404, "Not found");
        const bad = quantityProblem(p, line.quantity);
        if (bad) return field(res, `lines[${i}].quantity`, bad);
        lines.push({ productId: p.id, productName: p.name, unit: p.unit, quantity: line.quantity, price: p.price, total: lineTotal(p.price, line.quantity) });
      }
      const q = {
        id: db.nextId++,
        number: db.nextQuote++,
        resellerId: r.id,
        createdAt: now(),
        deliveryDate: deliveryDate(),
        expiresOn: input.expiresOn,
        status: "draft",
        total: sumTotals(lines),
        note: input.note ?? "",
        acceptedOrderId: null,
        sentAt: null,
        acceptedAt: null,
        declinedAt: null,
        cancelledAt: null,
        lines,
      };
      db.quotes.push(q);
      return send(res, 201, quoteView(q));
    }
    if (path === "/quotes") {
      const status = url.searchParams.get("status");
      const reseller = url.searchParams.get("resellerId");
      const found = db.quotes.filter((q) => (!status || quoteStatus(q) === status) && (!reseller || q.resellerId === Number(reseller)));
      return send(res, 200, [...found].reverse().map(quoteSummary));
    }
    if ((x = m(/^\/quotes\/(\d+)(?:\/(send|cancel))?$/))) {
      const q = db.quotes.find((y) => y.id === Number(x[1]));
      if (!q) return problem(res, 404, "Not found");
      if (!x[2]) return send(res, 200, quoteView(q));
      const current = quoteStatus(q);
      if (x[2] === "send") {
        if (current !== "draft") return problem(res, 409, `Quote is now ${current}`);
        Object.assign(q, { status: "sent", sentAt: now() });
      } else {
        if (current !== "draft" && current !== "sent") return problem(res, 409, `Quote is now ${current}`);
        Object.assign(q, { status: "cancelled", cancelledAt: now() });
      }
      return send(res, 200, quoteView(q));
    }
    if (path === "/production-run") {
      const date = url.searchParams.get("date");
      const confirmed = db.orders.filter((o) => o.deliveryDate === date && o.status === "confirmed");
      const lines = new Map();
      for (const o of confirmed)
        for (const l of o.lines) {
          const row = lines.get(l.productId) ?? { productId: l.productId, productName: l.productName, unit: l.unit, quantity: 0, orderCount: 0 };
          row.quantity = Math.round((row.quantity + l.quantity) * 1000) / 1000;
          row.orderCount += 1;
          lines.set(l.productId, row);
        }
      return send(res, 200, {
        date,
        lines: [...lines.values()].sort((a, b) => a.productId - b.productId),
        unconfirmedCount: db.orders.filter((o) => o.deliveryDate === date && o.status === "placed").length,
      });
    }
    return problem(res, 404, "Not found");
  } catch (error) {
    console.error(error);
    return problem(res, 500, "The request could not be completed");
  }
}).listen(port, "127.0.0.1", () => console.log(`fake api on ${port}`));
