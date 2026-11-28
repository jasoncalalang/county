// Request handling for the reseller's short-delivery report: the form, the report, and the
// approve and decline of a report the supplier filed. Kept apart from handlers.ts.
import {
  canFile,
  discrepancyApiErrors,
  discrepancyInput,
  receivedDefaults,
  resolveBody,
  canResolve,
  type FormErrors,
} from "../discrepancy";
import { resellerApi, type Fetch } from "./api";
import { confirmResolvePage, reportFormPage, reportPage, type ReportFormState } from "./discrepancy-render";
import { confirmationPage, unavailablePage } from "./render";
import { base, htmlResponse, inactive, orderId, PRICE_LIST_CACHE, redirect } from "./response";

const reportUrl = (token: string, id: number) => `${base(token)}/orders/${id}/discrepancy`;
const noErrors = (): FormErrors => ({ lines: {} });

type Options = { state?: ReportFormState; notice?: string; status?: number };

/**
 * The order's report, or the form for one, or why neither: read both fresh and show what is
 * true now. A form that carries errors is sent as 422; an untouched one is cacheable like the
 * price list, so that Back to it keeps its idempotency key.
 */
async function render(token: string, id: number, fetchImpl: Fetch | undefined, options: Options = {}): Promise<Response> {
  const api = resellerApi(fetchImpl);
  const [order, report] = await Promise.all([api.order(token, id), api.report(token, id)]);
  if (!order.ok) return order.status === 404 ? inactive() : htmlResponse(unavailablePage(token), 503);
  if (report.ok) return htmlResponse(reportPage(token, report.body, options.notice), options.status ?? 200);
  if (report.status !== 404) return htmlResponse(unavailablePage(token), 503);
  if (!canFile(order.body.status, false)) {
    return htmlResponse(confirmationPage(token, order.body, "Only a delivered order can be reported."), 409);
  }
  const { state } = options;
  const form = state ?? { received: receivedDefaults(order.body.lines), note: "", errors: noErrors() };
  const page = reportFormPage(token, order.body, crypto.randomUUID(), form);
  if (options.status || state) return htmlResponse(page, options.status ?? 422);
  return htmlResponse(page, 200, PRICE_LIST_CACHE);
}

export async function showDiscrepancy(token: string, rawId: string, fetchImpl?: Fetch): Promise<Response> {
  const id = orderId(rawId);
  if (id === null) return inactive();
  return render(token, id, fetchImpl);
}

export async function fileDiscrepancy(token: string, rawId: string, form: FormData, fetchImpl?: Fetch): Promise<Response> {
  const id = orderId(rawId);
  if (id === null) return inactive();
  const api = resellerApi(fetchImpl);
  const order = await api.order(token, id);
  if (!order.ok) return order.status === 404 ? inactive() : htmlResponse(unavailablePage(token), 503);

  const received: Record<string, string> = {};
  for (const [name, value] of form.entries()) {
    const match = /^r(\d+)$/.exec(name);
    if (match) received[match[1]] = String(value);
  }
  const note = String(form.get("note") ?? "").slice(0, 500);
  const key = String(form.get("idempotencyKey") ?? "");
  const again = (errors: FormErrors, status?: number) => render(token, id, fetchImpl, { state: { received, note, errors }, status });

  if (!/^[0-9a-f-]{36}$/i.test(key)) return again({ lines: {}, form: "Please report the problem again." });
  const input = discrepancyInput({ received, note }, order.body.lines, key);
  if (!input.ok) return again(input.errors);

  const filed = await api.fileReport(token, id, input.input);
  if (filed.ok) return redirect(reportUrl(token, id));
  if (filed.status === 404) return inactive();
  if (filed.status === 422) {
    const fields = discrepancyApiErrors(filed.problem.errors ?? {}, input.input.lines);
    const empty = !Object.keys(fields.lines).length && !fields.note && !fields.form;
    return again(empty ? { lines: {}, form: "Please check your report and send it again." } : fields);
  }
  if (filed.status === 409) {
    // Someone filed first, or the order moved on: show the report, or the order as it is now.
    const now = await api.report(token, id);
    if (now.ok) return redirect(reportUrl(token, id));
    return again({ lines: {}, form: "Please check your report and send it again." }, 409);
  }
  return htmlResponse(unavailablePage(token), 503);
}

async function resolve(
  token: string,
  rawId: string,
  action: "approve" | "decline",
  form: FormData,
  fetchImpl?: Fetch,
): Promise<Response> {
  const id = orderId(rawId);
  if (id === null) return inactive();
  const api = resellerApi(fetchImpl);
  const current = await api.report(token, id);
  // No report: the report page shows the form, or the inactive page if the order is not theirs.
  if (!current.ok) return current.status === 404 ? redirect(reportUrl(token, id)) : htmlResponse(unavailablePage(token), 503);
  // The API refuses these too; not asking keeps the confirmation for answers that can be sent.
  if (!canResolve(current.body, "reseller")) return redirect(reportUrl(token, id));
  const note = String(form.get("note") ?? "").slice(0, 500);
  if (form.get("confirmed") !== "yes") return htmlResponse(confirmResolvePage(token, current.body, action, note));

  const body = resolveBody(note);
  const answered = action === "approve" ? await api.approveReport(token, id, body) : await api.declineReport(token, id, body);
  if (answered.ok) return redirect(reportUrl(token, id));
  if (answered.status === 404) return inactive();
  if (answered.status === 409) {
    return render(token, id, fetchImpl, { notice: "This report can no longer be answered.", status: 409 });
  }
  if (answered.status === 422) {
    return render(token, id, fetchImpl, { notice: "Please check your note and answer again.", status: 422 });
  }
  return htmlResponse(unavailablePage(token), 503);
}

export const approveDiscrepancy = (token: string, rawId: string, form: FormData, fetchImpl?: Fetch) =>
  resolve(token, rawId, "approve", form, fetchImpl);

export const declineDiscrepancy = (token: string, rawId: string, form: FormData, fetchImpl?: Fetch) =>
  resolve(token, rawId, "decline", form, fetchImpl);
