// The reseller's short-delivery pages: the form, the report and the confirmation that comes
// before approving or declining. Every value from the API or the form goes through `html`.
import type { DiscrepancyView, OrderView, Unit } from "../api-types";
import { canResolve, partyLabel, receivedText, shortfallText, type FormErrors } from "../discrepancy";
import { formatDayTime } from "../format";
import { quantityAttrs } from "../quantity";
import { html, type Html } from "./html";
import { orderPath, page, reportBadge } from "./render";

export interface ReportFormState {
  /** Received quantities as typed, by product id. */
  received: Record<string, string>;
  note: string;
  errors: FormErrors;
}

const reportPath = (token: string, orderId: number) => `${orderPath(token, orderId)}/discrepancy`;
const sentence = (message: string) => (message.endsWith(".") ? message : `${message}.`);

/** The form: one received box per product, each starting at what was ordered. */
export function reportFormPage(token: string, order: OrderView, idempotencyKey: string, state: ReportFormState): string {
  const { errors } = state;
  const problems: { message: string; productId?: number }[] = [
    ...order.lines.flatMap((l) => {
      const message = errors.lines[String(l.productId)];
      return message ? [{ message: `${l.productName}: ${sentence(message)}`, productId: l.productId }] : [];
    }),
    ...(errors.note ? [{ message: sentence(errors.note) }] : []),
    ...(errors.form ? [{ message: sentence(errors.form) }] : []),
  ];
  const block = problems.length
    ? html`<div class="errors" role="alert" id="errors">${problems.map(
        (e) => html`<p>${e.productId === undefined ? e.message : html`<a href="#r${e.productId}">${e.message}</a>`}</p>`,
      )}</div>`
    : "";
  const title = `Report a problem with order #${order.number}`;
  return page(
    title,
    html`<div class="card"><h1 class="title">${title}</h1>
<p class="small">Order #${order.number} was delivered. Type what you received. Leave a product as it is if it arrived as ordered.</p></div>
${block}<form method="post" action="${reportPath(token, order.id)}" id="report">
<input type="hidden" name="idempotencyKey" value="${idempotencyKey}">
<div class="rows">${order.lines.map((l) => {
      const attrs = quantityAttrs(l.unit as Unit);
      const bad = Boolean(errors.lines[String(l.productId)]);
      return html`<label class="row${bad ? " bad" : ""}" for="r${l.productId}"><span><b>${l.productName}</b><span class="small num">Ordered ${l.quantity} ${l.unit}</span></span><input type="number" inputmode="${attrs.inputMode}" min="0" max="9999" step="${attrs.step}" id="r${l.productId}" name="r${l.productId}" value="${state.received[String(l.productId)] ?? String(l.quantity)}" aria-label="Received quantity of ${l.productName}"></label>`;
    })}</div>
<label class="block" for="note">Note for your supplier (optional)</label>
<textarea id="note" name="note" maxlength="500" rows="3">${state.note}</textarea>
<div class="bar"><button class="primary" type="submit">Send report</button></div>
</form>
<a class="back" href="${orderPath(token, order.id)}">← Back to order #${order.number}</a>`,
    false,
  );
}

function resolveForm(token: string, report: DiscrepancyView, action: "approve" | "decline", label: string, style: string): Html {
  return html`<form method="post" action="${reportPath(token, report.orderId)}/${action}"><button class="${style}" type="submit">${label}</button></form>`;
}

/** The report as the reseller sees it. `notice` reports a refused answer. */
export function reportPage(token: string, report: DiscrepancyView, notice?: string): string {
  const title = `Delivery report for order #${report.orderNumber}`;
  const who = partyLabel(report.filedBy, "reseller");
  const answered = report.resolvedBy && report.resolvedAt
    ? html`<p>${partyLabel(report.resolvedBy, "reseller")} ${report.status === "approved" ? "approved" : "declined"} this ${formatDayTime(report.resolvedAt)}</p>${report.resolutionNote ? html`<blockquote>${report.resolutionNote}</blockquote>` : ""}`
    : "";
  const next = canResolve(report, "reseller")
    ? html`<p class="small">Approve if this is right, or decline.</p><div class="actions">${resolveForm(token, report, "approve", "Approve", "primary")}${resolveForm(token, report, "decline", "Decline", "text danger")}</div>`
    : report.status === "open"
      ? html`<p class="small">Waiting for your supplier to answer.</p>`
      : "";
  return page(
    title,
    html`${notice ? html`<p class="errors" role="alert">${notice}</p>` : ""}<div class="card">
<h1 class="title">${title}</h1>
<p>${reportBadge(report.status)} <span class="small">${who} reported this ${formatDayTime(report.filedAt)}</span></p>
<table><thead><tr><th scope="col">Product</th><th scope="col" class="r">Received</th><th scope="col" class="r">Difference</th></tr></thead>
<tbody>${report.lines.map(
      (l) => html`<tr><td>${l.productName}</td><td class="r">${receivedText(l)}</td><td class="r">${shortfallText(l.shortfallTotal)}</td></tr>`,
    )}</tbody></table>
<p>In value: <b>${shortfallText(report.shortfallTotal)}</b></p>
${report.note ? html`<p class="small">${who === "You" ? "Your note" : "Note from your supplier"}</p><blockquote>${report.note}</blockquote>` : ""}
${answered}${next}
<p class="small">Order #${report.orderNumber} and its total do not change.</p>
</div>
<a class="back" href="${orderPath(token, report.orderId)}">← Back to order #${report.orderNumber}</a>`,
    false,
  );
}

/** The one-button confirmation before an answer is sent; it carries the optional note. */
export function confirmResolvePage(token: string, report: DiscrepancyView, action: "approve" | "decline", note: string): string {
  const verb = action === "approve" ? "Approve" : "Decline";
  const title = `${verb} this report?`;
  return page(
    title,
    html`<div class="card"><h1>${title}</h1><p class="small">Order #${report.orderNumber}: ${shortfallText(report.shortfallTotal)}. Your supplier will see your answer.</p>
<form method="post" action="${reportPath(token, report.orderId)}/${action}"><input type="hidden" name="confirmed" value="yes">
<label class="block" for="note">Note for your supplier (optional)</label>
<textarea id="note" name="note" maxlength="500" rows="3">${note}</textarea>
<p><button class="primary" type="submit">${verb} report</button></p></form></div>
<a class="back" href="${reportPath(token, report.orderId)}">← Back to the report</a>`,
    false,
  );
}
