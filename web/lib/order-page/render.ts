// The reseller's pages (interface design, section 7). Every page is a full HTML document with
// the stylesheet and the optional script inlined; every action is a link or a form post.
import type { CatalogView, DiscrepancyView, OrderView, QuoteStatus, QuoteSummaryView, QuoteView } from "../api-types";
import { DISCREPANCY_STATUS_LABEL } from "../discrepancy";
import { formatClock, formatMoney, formatShortDate, QUOTE_STATUS_LABEL, quoteNumber, STATUS_LABEL, type Status } from "../format";
import { quantityAttrs } from "../quantity";
import { html, raw, type Html } from "./html";
import { ORDER_PAGE_SCRIPT } from "./script";
import { ORDER_PAGE_CSS } from "./styles";

export function page(title: string, body: Html, withScript = true): string {
  return `<!doctype html>${html`<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="no-referrer"><meta name="robots" content="noindex"><title>${title}</title><style>${raw(ORDER_PAGE_CSS)}</style></head><body>${body}${withScript ? html`<script>${raw(ORDER_PAGE_SCRIPT)}</script>` : ""}</body></html>`}`;
}

function badge(status: Status): Html {
  return html`<span class="badge ${status}">${STATUS_LABEL[status]}</span>`;
}

function quoteBadge(status: QuoteStatus): Html {
  return html`<span class="badge ${status}">${QUOTE_STATUS_LABEL[status]}</span>`;
}

function quotePath(token: string, id: number): string {
  return `${orderPath(token)}/quotes/${id}`;
}

export function orderPath(token: string, id?: number): string {
  const base = `/order/${encodeURIComponent(token)}`;
  return id === undefined ? base : `${base}/orders/${id}`;
}

function cancelForm(token: string, order: { id: number; number: number }, back: "list" | "order"): Html {
  return html`<form method="post" action="${orderPath(token, order.id)}/cancel" data-confirm="Cancel order #${order.number}?"><input type="hidden" name="back" value="${back}"><button class="text danger" type="submit">${back === "list" ? "Cancel" : "Cancel order"}</button></form>`;
}

export interface FormState {
  /** Quantities as typed, by product id. */
  quantities: Record<string, string>;
  note: string;
  /** Messages for the error block; `productId` links the message to its row. */
  errors: { message: string; productId?: number }[];
}

export const EMPTY_FORM: FormState = { quantities: {}, note: "", errors: [] };

/** 7.1 price list, and 7.2 when `state.errors` is not empty. */
// The form does not set autocomplete="off": that would stop the browser restoring the typed
// quantities when the reseller presses Back.
export function priceListPage(
  token: string,
  catalog: CatalogView,
  idempotencyKey: string,
  state: FormState = EMPTY_FORM,
  quotes: QuoteSummaryView[] = [],
): string {
  const bad = new Set(state.errors.flatMap((e) => (e.productId === undefined ? [] : [e.productId])));
  const errors = state.errors.length
    ? html`<div class="errors" role="alert" id="errors">${state.errors.map(
        (e) =>
          html`<p>${e.productId === undefined ? e.message : html`<a href="#q${e.productId}">${e.message}</a>`}</p>`,
      )}</div>`
    : "";

  const form = catalog.products.length
    ? html`<form method="post" action="${orderPath(token)}" id="order">
<input type="hidden" name="idempotencyKey" value="${idempotencyKey}">
<div class="rows">${catalog.products.map(
        (p) => {
          const attrs = quantityAttrs(p.unit);
          return html`<label class="row${bad.has(p.id) ? " bad" : ""}" for="q${p.id}"><span><b>${p.name}</b><span class="small num">${p.unit} · ${formatMoney(p.price)}</span></span><input type="number" inputmode="${attrs.inputMode}" min="0" max="9999" step="${attrs.step}" id="q${p.id}" name="q${p.id}" value="${state.quantities[String(p.id)] ?? "0"}" data-price="${p.price}" aria-label="Quantity of ${p.name}"></label>`;
        },
      )}</div>
<label class="block" for="note">Note for your supplier (optional)</label>
<textarea id="note" name="note" maxlength="500" rows="3">${state.note}</textarea>
<div class="bar"><p class="total num" id="total" aria-live="polite" hidden></p><button class="primary" type="submit">Place order</button></div>
</form>`
    : html`<p class="card">No products available yet. Please contact your supplier.</p>`;

  const offers = quotes.length
    ? html`<h2>Quotes for you</h2><ul class="recent">${quotes.map(
        (q) =>
          html`<li><a href="${quotePath(token, q.id)}" class="num">${quoteNumber(q.number)} · ${formatMoney(q.total)} · accept by ${formatShortDate(q.expiresOn)}</a></li>`,
      )}</ul>`
    : "";

  const recent = catalog.recentOrders.length
    ? html`<h2>Your recent orders</h2><ul class="recent">${catalog.recentOrders.map(
        (o) =>
          html`<li><a href="${orderPath(token, o.id)}" class="num">#${o.number} · ${formatShortDate(o.deliveryDate)} · ${formatMoney(o.total)}</a>${badge(o.status)}${o.status === "placed" ? cancelForm(token, o, "list") : ""}</li>`,
      )}</ul>`
    : "";

  return page(
    catalog.businessName,
    html`<header><h1>${catalog.businessName}</h1><p class="small">Ordering as ${catalog.resellerName}</p></header>
<div class="banner"><strong>Delivery on ${formatShortDate(catalog.deliveryDate)}</strong><span class="small">Orders placed after ${formatClock(catalog.cutoff)} go to the next delivery day.</span></div>
${offers}${errors}${form}${recent}`,
  );
}

function reportPath(token: string, orderId: number): string {
  return `${orderPath(token, orderId)}/discrepancy`;
}

export function reportBadge(status: DiscrepancyView["status"]): Html {
  return html`<span class="badge ${status}">${DISCREPANCY_STATUS_LABEL[status]}</span>`;
}

/** What a delivered order's page says about its report: an offer to file one, or its status. */
function reportSection(token: string, order: OrderView, report: DiscrepancyView | null): Html {
  return report
    ? html`<p>Delivery report ${reportBadge(report.status)} <a href="${reportPath(token, order.id)}">View report</a></p>`
    : html`<p><a href="${reportPath(token, order.id)}">Report a problem with this delivery</a></p>`;
}

/**
 * 7.3 confirmation. `notice` replaces the "what happens next" line, e.g. after a refused cancel.
 * `report` is the order's short-delivery report: null when there is none, undefined when it
 * could not be read, which leaves the section out.
 */
export function confirmationPage(token: string, order: OrderView, notice?: string, report?: DiscrepancyView | null): string {
  const heading =
    order.status === "cancelled" ? `Order #${order.number} cancelled` : `Order #${order.number} placed`;
  const placed = order.status === "placed";
  return page(
    heading,
    html`${notice ? html`<p class="errors" role="alert">${notice}</p>` : ""}<div class="card">
<h1 class="title">${heading}</h1>
<p>Delivery on ${formatShortDate(order.deliveryDate)} ${badge(order.status)}</p>
<table><thead><tr><th scope="col">Product</th><th scope="col" class="r">Qty × price</th><th scope="col" class="r">Total</th></tr></thead>
<tbody>${order.lines.map(
      (l) =>
        html`<tr><td>${l.productName}</td><td class="r">${l.quantity} ${l.unit} × ${formatMoney(l.price)}</td><td class="r">${formatMoney(l.total)}</td></tr>`,
    )}</tbody>
<tfoot><tr><td colspan="2">Total</td><td class="r">${formatMoney(order.total)}</td></tr></tfoot></table>
${order.note ? html`<p class="small">Your note</p><blockquote>${order.note}</blockquote>` : ""}
${placed ? html`<p class="small">Your supplier will confirm this order. You can cancel it until then.</p>` : ""}
${order.status === "delivered" && report !== undefined ? reportSection(token, order, report) : ""}
</div>
<a class="back" href="${orderPath(token)}">← Back to price list</a>
${placed ? cancelForm(token, order, "order") : ""}`,
  );
}

/** The one-button confirmation shown when cancel is posted without the script. */
export function confirmCancelPage(token: string, order: { id: number; number: number }, back: "list" | "order"): string {
  return page(
    `Cancel order #${order.number}?`,
    html`<div class="card"><h1>Cancel order #${order.number}?</h1>
<form method="post" action="${orderPath(token, order.id)}/cancel"><input type="hidden" name="back" value="${back}"><input type="hidden" name="confirmed" value="yes"><p><button class="primary" type="submit">Cancel order</button></p></form></div>
<a class="back" href="${back === "list" ? orderPath(token) : orderPath(token, order.id)}">← Keep the order</a>`,
    false,
  );
}

/** 7.4: no business name, no other link. */
export function inactivePage(): string {
  return page(
    "This link is no longer active",
    html`<div class="center"><h1>This link is no longer active.</h1><p>Please contact your supplier.</p></div>`,
    false,
  );
}

/** A request to the API failed for a reason the reseller cannot fix by editing the form. */
export function unavailablePage(token: string): string {
  return page(
    "Please try again",
    html`<div class="errors" role="alert"><p>Could not reach your supplier's price list. Please try again.</p></div><a class="back" href="${orderPath(token)}">← Back to price list</a>`,
    false,
  );
}

/**
 * A quote on the reseller's link. `catalog` gives the business name and the delivery date an
 * order placed now gets, which is the date accepting gives (the quote's own date is the
 * estimate from when it was made). `notice` reports a refused accept or decline.
 */
export function quotePage(
  token: string,
  catalog: Pick<CatalogView, "businessName" | "deliveryDate">,
  quote: QuoteView,
  idempotencyKey: string,
  notice?: string,
): string {
  const label = quoteNumber(quote.number);
  const outcome: Record<Exclude<QuoteStatus, "sent">, Html> = {
    accepted: html`<p>You accepted this quote.${
      quote.acceptedOrderId ? html` <a href="${orderPath(token, quote.acceptedOrderId)}">See your order</a>` : ""
    }</p>`,
    declined: html`<p>You declined this quote.</p>`,
    expired: html`<p>This quote ended on ${formatShortDate(quote.expiresOn)}. Ask your supplier for a new one.</p>`,
    cancelled: html`<p>Your supplier withdrew this quote.</p>`,
    draft: html`<p>This quote is not ready yet.</p>`,
  };
  const sent = quote.status === "sent";
  return page(
    `Quote ${label}`,
    html`<header><h1>${catalog.businessName}</h1></header>
${notice ? html`<p class="errors" role="alert">${notice}</p>` : ""}<div class="card">
<h1 class="title">Quote ${label}</h1>
<p>${quoteBadge(quote.status)}${sent ? html` <span class="small">Accept by ${formatShortDate(quote.expiresOn)}</span>` : ""}</p>
<table><thead><tr><th scope="col">Product</th><th scope="col" class="r">Qty × price</th><th scope="col" class="r">Total</th></tr></thead>
<tbody>${quote.lines.map(
      (l) =>
        html`<tr><td>${l.productName}</td><td class="r">${l.quantity} ${l.unit} × ${formatMoney(l.price)}</td><td class="r">${formatMoney(l.total)}</td></tr>`,
    )}</tbody>
<tfoot><tr><td colspan="2">Total</td><td class="r">${formatMoney(quote.total)}</td></tr></tfoot></table>
${quote.note ? html`<p class="small">Note from your supplier</p><blockquote>${quote.note}</blockquote>` : ""}
${
  quote.status === "sent"
    ? html`<p class="small">Accepting places this order at these prices, for delivery on ${formatShortDate(catalog.deliveryDate)}.</p>
<div class="actions"><form method="post" action="${quotePath(token, quote.id)}/accept"><input type="hidden" name="idempotencyKey" value="${idempotencyKey}"><button class="primary" type="submit">Accept and place order</button></form>
<form method="post" action="${quotePath(token, quote.id)}/decline" data-confirm="Decline quote ${label}?"><button class="text danger" type="submit">Decline</button></form></div>`
    : outcome[quote.status]
}
</div>
<a class="back" href="${orderPath(token)}">← Back to price list</a>`,
  );
}

/** The one-button confirmation shown when decline is posted without the script. */
export function confirmDeclinePage(token: string, quote: { id: number; number: number }): string {
  const label = quoteNumber(quote.number);
  return page(
    `Decline quote ${label}?`,
    html`<div class="card"><h1>Decline quote ${label}?</h1><p class="small">Your supplier will see that you declined.</p>
<form method="post" action="${quotePath(token, quote.id)}/decline"><input type="hidden" name="confirmed" value="yes"><p><button class="primary" type="submit">Decline quote</button></p></form></div>
<a class="back" href="${quotePath(token, quote.id)}">← Keep the quote</a>`,
    false,
  );
}
