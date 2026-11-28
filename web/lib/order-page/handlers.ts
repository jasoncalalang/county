// Request handling for the reseller routes under app/order. Kept apart from the route files so
// the tests can pass a fake fetch.
import { parseQuantity } from "../quantity";
import { resellerApi, type Fetch } from "./api";
import { base, htmlResponse, inactive, orderId, PRICE_LIST_CACHE, redirect } from "./response";
import {
  confirmCancelPage,
  confirmDeclinePage,
  confirmationPage,
  priceListPage,
  quotePage,
  unavailablePage,
  type FormState,
} from "./render";

export async function showPriceList(token: string, fetchImpl?: Fetch, state?: FormState): Promise<Response> {
  const api = resellerApi(fetchImpl);
  const [catalog, quotes] = await Promise.all([api.catalog(token), api.quotes(token)]);
  if (!catalog.ok) return catalog.status === 404 ? inactive() : htmlResponse(unavailablePage(token), 503);
  // The price list still works when the quotes cannot be read; they are left out.
  const offers = quotes.ok ? quotes.body : [];
  if (state) {
    // Only rows still on the price list can carry a link to themselves.
    const listed = new Set(catalog.body.products.map((p) => p.id));
    state = { ...state, errors: state.errors.map((e) => (e.productId && !listed.has(e.productId) ? { message: e.message } : e)) };
  }
  const page = priceListPage(token, catalog.body, crypto.randomUUID(), state, offers);
  return state?.errors.length ? htmlResponse(page, 422) : htmlResponse(page, 200, PRICE_LIST_CACHE);
}

export async function placeOrder(token: string, form: FormData, fetchImpl?: Fetch): Promise<Response> {
  const api = resellerApi(fetchImpl);
  // The units say which rows take decimals; the API applies the same rule again.
  const catalog = await api.catalog(token);
  if (!catalog.ok) return catalog.status === 404 ? inactive() : htmlResponse(unavailablePage(token), 503);
  const units = new Map(catalog.body.products.map((p) => [p.id, p.unit]));
  const note = String(form.get("note") ?? "").slice(0, 500);
  const key = String(form.get("idempotencyKey") ?? "");
  const quantities: Record<string, string> = {};
  const errors: FormState["errors"] = [];
  const lines: { productId: number; quantity: number }[] = [];
  for (const [name, value] of form.entries()) {
    const match = /^q(\d+)$/.exec(name);
    if (!match) continue;
    const productId = Number(match[1]);
    const typed = String(value).trim();
    quantities[match[1]] = typed;
    const parsed = parseQuantity(typed, units.get(productId));
    if ("error" in parsed) errors.push({ productId, message: `${parsed.error}.` });
    else if (parsed.quantity !== null) lines.push({ productId, quantity: parsed.quantity });
  }
  const again = (more: FormState["errors"]) =>
    showPriceList(token, fetchImpl, { quantities, note, errors: [...errors, ...more] });

  if (errors.length) return again([]);
  if (lines.length === 0) return again([{ message: "Add at least one product." }]);
  if (!/^[0-9a-f-]{36}$/i.test(key)) return again([{ message: "Please place your order again." }]);

  const placed = await api.place(token, { lines, note: note || undefined, idempotencyKey: key });
  if (placed.ok) return redirect(`${base(token)}/orders/${placed.body.id}`);
  if (placed.status === 404) {
    // A product id that is not in this business is also a 404; tell the two apart by the token.
    const catalog = await api.catalog(token);
    if (!catalog.ok) return inactive();
    return again([{ message: "Some products are no longer available." }]);
  }
  if (placed.status === 422) {
    // Field errors are keyed by JSON path into what was sent, e.g. `lines[1].productId`:
    // "Tocino is no longer available". A deactivated product is gone from the fresh price
    // list, so its message stands in the error block with a link only when the row remains.
    const fields = Object.entries(placed.problem.errors ?? {});
    const more = fields.length
      ? fields.map(([path, message]) => {
          const index = /^lines\[(\d+)\]/.exec(path);
          const productId = index ? lines[Number(index[1])]?.productId : undefined;
          return { message: message.endsWith(".") ? message : `${message}.`, productId };
        })
      : [{ message: "Please check your order and place it again." }];
    return again(more);
  }
  if (placed.status === 409) return again([{ message: "Please check your order and place it again." }]);
  return htmlResponse(unavailablePage(token), 503);
}

export async function showOrder(token: string, rawId: string, fetchImpl?: Fetch): Promise<Response> {
  const id = orderId(rawId);
  if (id === null) return inactive();
  const api = resellerApi(fetchImpl);
  const order = await api.order(token, id);
  if (!order.ok) return order.status === 404 ? inactive() : htmlResponse(unavailablePage(token), 503);
  if (order.body.status !== "delivered") return htmlResponse(confirmationPage(token, order.body));
  const report = await api.report(token, id);
  // No report is a 404; any other failure leaves the report out rather than the order.
  const known = report.ok ? report.body : report.status === 404 ? null : undefined;
  return htmlResponse(confirmationPage(token, order.body, undefined, known));
}

export async function cancelOrder(token: string, rawId: string, form: FormData, fetchImpl?: Fetch): Promise<Response> {
  const id = orderId(rawId);
  if (id === null) return inactive();
  const api = resellerApi(fetchImpl);
  const back = form.get("back") === "list" ? "list" : "order";
  const target = back === "list" ? base(token) : `${base(token)}/orders/${id}`;

  const current = await api.order(token, id);
  if (!current.ok) return current.status === 404 ? inactive() : htmlResponse(unavailablePage(token), 503);
  if (form.get("confirmed") !== "yes") return htmlResponse(confirmCancelPage(token, current.body, back));

  const cancelled = await api.cancel(token, id);
  if (cancelled.ok) return redirect(target);
  if (cancelled.status === 409) {
    const now = await api.order(token, id);
    if (now.ok && now.body.status === "cancelled") return redirect(target);
    if (now.ok)
      return htmlResponse(
        confirmationPage(
          token,
          now.body,
          `Order #${now.body.number} is ${now.body.status}. Contact your supplier to change it.`,
        ),
        409,
      );
  }
  if (cancelled.status === 404) return inactive();
  return htmlResponse(unavailablePage(token), 503);
}

const quoteBase = (token: string, id: number) => `${base(token)}/quotes/${id}`;

/** The quote page with a fresh key, or the page a failed read calls for. */
async function renderQuote(token: string, id: number, fetchImpl?: Fetch, notice?: string, status = 200): Promise<Response> {
  const api = resellerApi(fetchImpl);
  const [catalog, quote] = await Promise.all([api.catalog(token), api.quote(token, id)]);
  if (!catalog.ok) return catalog.status === 404 ? inactive() : htmlResponse(unavailablePage(token), 503);
  if (!quote.ok) return quote.status === 404 ? inactive() : htmlResponse(unavailablePage(token), 503);
  const page = quotePage(token, catalog.body, quote.body, crypto.randomUUID(), notice);
  // Like the price list, an untouched quote page may come back from the back/forward cache, so
  // that Back and accept again reuses its key and returns the same order.
  return htmlResponse(page, status, notice ? "no-store" : PRICE_LIST_CACHE);
}

export async function showQuote(token: string, rawId: string, fetchImpl?: Fetch): Promise<Response> {
  const id = orderId(rawId);
  if (id === null) return inactive();
  return renderQuote(token, id, fetchImpl);
}

export async function acceptQuote(token: string, rawId: string, form: FormData, fetchImpl?: Fetch): Promise<Response> {
  const id = orderId(rawId);
  if (id === null) return inactive();
  const key = String(form.get("idempotencyKey") ?? "");
  if (!/^[0-9a-f-]{36}$/i.test(key)) return renderQuote(token, id, fetchImpl, "Please accept the quote again.", 422);

  const accepted = await resellerApi(fetchImpl).accept(token, id, key);
  if (accepted.ok) return redirect(`${base(token)}/orders/${accepted.body.id}`);
  if (accepted.status === 404) return inactive();
  if (accepted.status === 409 || accepted.status === 422) {
    // Expired, declined or withdrawn since the page was shown, or a spent key: show it as it is now.
    return renderQuote(token, id, fetchImpl, "This quote can no longer be accepted.", 409);
  }
  return htmlResponse(unavailablePage(token), 503);
}

export async function declineQuote(token: string, rawId: string, form: FormData, fetchImpl?: Fetch): Promise<Response> {
  const id = orderId(rawId);
  if (id === null) return inactive();
  const api = resellerApi(fetchImpl);
  const current = await api.quote(token, id);
  if (!current.ok) return current.status === 404 ? inactive() : htmlResponse(unavailablePage(token), 503);
  if (form.get("confirmed") !== "yes") return htmlResponse(confirmDeclinePage(token, current.body));

  const declined = await api.decline(token, id);
  if (declined.ok) return redirect(quoteBase(token, id));
  if (declined.status === 404) return inactive();
  if (declined.status === 409) {
    const now = await api.quote(token, id);
    if (now.ok && now.body.status === "declined") return redirect(quoteBase(token, id));
    return renderQuote(token, id, fetchImpl, "This quote can no longer be declined.", 409);
  }
  return htmlResponse(unavailablePage(token), 503);
}
