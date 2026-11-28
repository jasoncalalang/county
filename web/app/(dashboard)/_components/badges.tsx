import type { QuoteStatus } from "@/lib/api-types";
import { QUOTE_STATUS_LABEL, STATUS_LABEL, type Status } from "@/lib/format";
import ui from "../../ui.module.css";

export function StatusBadge({ status }: { status: Status }) {
  return <span className={`${ui.badge} ${ui[status]}`}>{STATUS_LABEL[status]}</span>;
}

/** Paid shows only once an order is delivered. */
export function PaidBadge({ status, paid }: { status: Status; paid: boolean }) {
  if (status !== "delivered") return null;
  return <span className={`${ui.badge} ${paid ? ui.paid : ui.unpaid}`}>{paid ? "Paid" : "Unpaid"}</span>;
}

export function QuoteBadge({ status }: { status: QuoteStatus }) {
  return <span className={`${ui.badge} ${ui[status]}`}>{QUOTE_STATUS_LABEL[status]}</span>;
}
