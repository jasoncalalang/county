"use client";
// The owner's "Report a short delivery" form: what actually arrived, a box per product starting
// at what was ordered. It mounts only while its dialog is open, so each opening starts fresh
// with a new idempotency key.
import { useState } from "react";
import type { DiscrepancyView, OrderView, Unit } from "@/lib/api-types";
import { ApiError, useApi } from "@/lib/dashboard/api";
import { discrepancyApiErrors, discrepancyInput, receivedDefaults, type FormErrors } from "@/lib/discrepancy";
import { quantityAttrs } from "@/lib/quantity";
import { DialogActions } from "./dialog";
import { useShell } from "./shell";
import ui from "../../ui.module.css";

export function DiscrepancyForm({
  order,
  onFiled,
  onRefused,
  onClose,
}: {
  order: Pick<OrderView, "id" | "lines">;
  onFiled: (report: DiscrepancyView) => void;
  /** The API said 409: the order moved on or has a report already. */
  onRefused: (message: string) => void;
  onClose: () => void;
}) {
  const api = useApi();
  const { toast } = useShell();
  const [received, setReceived] = useState(() => receivedDefaults(order.lines));
  const [note, setNote] = useState("");
  const [errors, setErrors] = useState<FormErrors>({ lines: {} });
  const [key] = useState(() => crypto.randomUUID());
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const checked = discrepancyInput({ received, note }, order.lines, key);
    if (!checked.ok) {
      setErrors(checked.errors);
      return;
    }
    setErrors({ lines: {} });
    setBusy(true);
    try {
      onFiled(await api.post<DiscrepancyView>(`/orders/${order.id}/discrepancy`, checked.input));
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) onRefused(error.message);
      else if (error instanceof ApiError && Object.keys(error.fields).length) setErrors(discrepancyApiErrors(error.fields, checked.input.lines));
      else if (!(error instanceof ApiError && error.status === 401)) toast("Could not save, try again", "error");
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} noValidate>
      <p className={ui.small}>Type what arrived. Leave a product as it is if it arrived as ordered.</p>
      <div className={ui.lineRows} style={{ marginTop: 12 }}>
        {order.lines.map((line) => {
          const id = String(line.productId);
          const error = errors.lines[id];
          const attrs = quantityAttrs(line.unit as Unit);
          return (
            <div key={line.productId} className={ui.lineRow}>
              <span>
                <label htmlFor={`received-${id}`}>
                  <strong>{line.productName}</strong>
                </label>
                <br />
                <span className={`${ui.small} ${ui.num}`}>
                  Ordered {line.quantity} {line.unit}
                </span>
                {error && (
                  <>
                    <br />
                    <span className={ui.fieldError}>{error}</span>
                  </>
                )}
              </span>
              <input
                id={`received-${id}`}
                type="number"
                inputMode={attrs.inputMode}
                min={0}
                max={9999}
                step={attrs.step}
                aria-label={`Received quantity of ${line.productName}`}
                aria-invalid={Boolean(error)}
                value={received[id] ?? ""}
                onChange={(e) => setReceived((all) => ({ ...all, [id]: e.target.value }))}
              />
            </div>
          );
        })}
      </div>
      <div className={ui.field}>
        <label htmlFor="report-note" className={ui.label}>
          Note for the reseller (optional)
        </label>
        <textarea
          id="report-note"
          maxLength={500}
          rows={3}
          value={note}
          onChange={(e) => setNote(e.target.value)}
          aria-invalid={Boolean(errors.note)}
        />
        {errors.note && <span className={ui.fieldError}>{errors.note}</span>}
      </div>
      {errors.form && (
        <p className={ui.fieldError} role="alert">
          {errors.form}
        </p>
      )}
      <DialogActions>
        <button type="button" className={ui.secondary} onClick={onClose}>
          Cancel
        </button>
        <button type="submit" className={ui.primary} disabled={busy}>
          File report
        </button>
      </DialogActions>
    </form>
  );
}
