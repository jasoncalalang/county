"use client";
// The delivery report on the order detail: the report with approve and decline when the
// reseller filed it and it is open, or "Report a short delivery" for a delivered order with no
// report. What the other party wrote is shown as React text, never as markup.
import { useCallback, useEffect, useId, useState } from "react";
import type { DiscrepancyView, OrderView } from "@/lib/api-types";
import { ApiError, useApi } from "@/lib/dashboard/api";
import { canFile, canResolve, receivedText, reportTimeline, resolveBody, shortfallText } from "@/lib/discrepancy";
import { ReportBadge } from "./badges";
import { Dialog, DialogActions } from "./dialog";
import { DiscrepancyForm } from "./discrepancy-form";
import { useShell } from "./shell";
import ui from "../../ui.module.css";

type Modal = "file" | "approve" | "decline" | null;

export function DiscrepancySection({ order }: { order: Pick<OrderView, "id" | "number" | "status" | "lines"> }) {
  const api = useApi();
  const { toast } = useShell();
  const headingId = useId();
  const delivered = order.status === "delivered";
  // undefined until read; null when the order has no report (the API answers 404).
  const [report, setReport] = useState<DiscrepancyView | null | undefined>(undefined);
  const [modal, setModal] = useState<Modal>(null);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    if (!api.ready || !delivered) return;
    try {
      setReport(await api.get<DiscrepancyView>(`/orders/${order.id}/discrepancy`));
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) setReport(null);
      else toast("Could not load the delivery report, try again", "error");
    }
  }, [api, order.id, delivered, toast]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load();
  }, [load]);

  /** The API refused: show what is true now. */
  async function refused(message: string) {
    setModal(null);
    toast(message, "error");
    await load();
  }

  async function answer(action: "approve" | "decline") {
    setBusy(true);
    setModal(null);
    try {
      setReport(await api.post<DiscrepancyView>(`/orders/${order.id}/discrepancy/${action}`, resolveBody(note)));
      toast(`Report ${action === "approve" ? "approved" : "declined"}`);
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) await refused(error.message);
      else if (!(error instanceof ApiError && error.status === 401)) toast("Could not save, try again", "error");
    } finally {
      setBusy(false);
    }
  }

  function ask(next: "approve" | "decline") {
    setNote("");
    setModal(next);
  }

  if (!delivered || report === undefined) return null;

  const dialogs = (
    <>
      <Dialog open={modal === "file"} title="Report a short delivery" onClose={() => setModal(null)}>
        <DiscrepancyForm
          order={order}
          onClose={() => setModal(null)}
          onRefused={refused}
          onFiled={(filed) => {
            setReport(filed);
            setModal(null);
            toast("Delivery report filed");
          }}
        />
      </Dialog>
      {(["approve", "decline"] as const).map((action) => (
        <Dialog
          key={action}
          open={modal === action}
          title={`${action === "approve" ? "Approve" : "Decline"} this report?`}
          onClose={() => setModal(null)}
        >
          <p>The reseller will see your answer.</p>
          <div className={ui.field} style={{ marginTop: 12 }}>
            <label htmlFor={`${action}-note`} className={ui.label}>
              Note for the reseller (optional)
            </label>
            <textarea id={`${action}-note`} maxLength={500} rows={3} value={note} onChange={(e) => setNote(e.target.value)} />
          </div>
          <DialogActions>
            <button type="button" className={ui.secondary} onClick={() => setModal(null)}>
              Keep
            </button>
            <button
              type="button"
              className={action === "decline" ? `${ui.primary} ${ui.dangerPrimary}` : ui.primary}
              onClick={() => answer(action)}
            >
              {action === "approve" ? "Approve report" : "Decline report"}
            </button>
          </DialogActions>
        </Dialog>
      ))}
    </>
  );

  if (report === null) {
    return (
      <section className={ui.report} aria-labelledby={headingId}>
        <h2 id={headingId} className={ui.heading}>
          Delivery report
        </h2>
        <p className={ui.small}>Nothing has been reported about this delivery.</p>
        {canFile(order.status, false) && (
          <button type="button" className={ui.secondary} style={{ marginTop: 8 }} onClick={() => setModal("file")}>
            Report a short delivery
          </button>
        )}
        {dialogs}
      </section>
    );
  }

  return (
    <section className={ui.report} aria-labelledby={headingId}>
      <div className={ui.badges}>
        <h2 id={headingId} className={ui.heading}>
          Delivery report
        </h2>
        <ReportBadge status={report.status} />
      </div>
      <table className={ui.table}>
        <thead>
          <tr>
            <th scope="col">Product</th>
            <th scope="col" className={ui.right}>
              Received
            </th>
            <th scope="col" className={ui.right}>
              Difference
            </th>
          </tr>
        </thead>
        <tbody>
          {report.lines.map((line) => (
            <tr key={line.productId}>
              <td>{line.productName}</td>
              <td className={ui.right}>{receivedText(line)}</td>
              <td className={ui.right}>{shortfallText(line.shortfallTotal)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p>
        In value: <strong>{shortfallText(report.shortfallTotal)}</strong>
      </p>
      {report.note && <blockquote className={ui.quote}>{report.note}</blockquote>}
      <ul className={ui.history}>
        {reportTimeline(report, "owner").map((line) => (
          <li key={line}>{line}</li>
        ))}
      </ul>
      {report.resolutionNote && <blockquote className={ui.quote}>{report.resolutionNote}</blockquote>}
      {canResolve(report, "owner") ? (
        busy ? (
          <span className={ui.small} role="status">
            Saving…
          </span>
        ) : (
          <div className={ui.actions}>
            <button type="button" className={ui.dangerButton} onClick={() => ask("decline")}>
              Decline
            </button>
            <button type="button" className={ui.secondary} onClick={() => ask("approve")}>
              Approve
            </button>
          </div>
        )
      ) : (
        report.status === "open" && <p className={ui.small}>Waiting for the reseller to answer.</p>
      )}
      <p className={ui.small}>Order #{order.number} and its total do not change.</p>
      {dialogs}
    </section>
  );
}
