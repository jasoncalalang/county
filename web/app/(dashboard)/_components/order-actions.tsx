"use client";
// Confirm, deliver, cancel and mark paid, shared by the orders list and the order detail.
// The main action sits at the right edge with Cancel before it (UI design 6.3). Only cancel
// and deliver ask; a 409 shows the order's real status (functional spec 3.4).
import { useState } from "react";
import type { OrderSummaryView, OrderView } from "@/lib/api-types";
import { ApiError, useApi } from "@/lib/dashboard/api";
import { Dialog, DialogActions } from "./dialog";
import { useShell } from "./shell";
import ui from "../../ui.module.css";

type Order = Pick<OrderSummaryView, "id" | "number" | "status" | "paid">;
type Ask = { kind: "deliver" | "cancel"; order: Order } | null;

export function useOrderActions(onUpdated: (order: OrderView) => void) {
  const api = useApi();
  const { toast, refreshPlaced } = useShell();
  const [busy, setBusy] = useState<number | null>(null);
  const [ask, setAsk] = useState<Ask>(null);

  async function run(order: Order, action: string, done: string, body?: unknown) {
    setAsk(null);
    setBusy(order.id);
    try {
      onUpdated(await api.post<OrderView>(`/orders/${order.id}/${action}`, body));
      toast(`Order #${order.number} ${done}`);
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        try {
          const now = await api.get<OrderView>(`/orders/${order.id}`);
          onUpdated(now);
          toast(`This order is now ${now.status === "delivered" && now.paid ? "paid" : now.status}`);
        } catch {
          toast("Could not save, try again", "error");
        }
      } else if (!(error instanceof ApiError && error.status === 401)) {
        toast("Could not save, try again", "error");
      }
    } finally {
      setBusy(null);
      refreshPlaced();
    }
  }

  function buttons(order: Order) {
    if (busy === order.id) {
      return (
        <span className={ui.small} role="status">
          Saving…
        </span>
      );
    }
    const cancel = (
      <button type="button" className={ui.dangerButton} onClick={() => setAsk({ kind: "cancel", order })}>
        Cancel
      </button>
    );
    switch (order.status) {
      case "placed":
        return (
          <div className={ui.actions}>
            {cancel}
            <button type="button" className={ui.secondary} onClick={() => run(order, "confirm", "confirmed")}>
              Confirm
            </button>
          </div>
        );
      case "confirmed":
        return (
          <div className={ui.actions}>
            {cancel}
            <button type="button" className={ui.secondary} onClick={() => setAsk({ kind: "deliver", order })}>
              Deliver
            </button>
          </div>
        );
      case "delivered":
        return order.paid ? null : (
          <div className={ui.actions}>
            <button type="button" className={ui.secondary} onClick={() => run(order, "mark-paid", "marked paid")}>
              Mark paid
            </button>
          </div>
        );
      default:
        return null;
    }
  }

  const dialogs = (
    <>
      <Dialog open={ask?.kind === "deliver"} title={`Was order #${ask?.order.number} paid?`} onClose={() => setAsk(null)}>
        <p>Choose No if the reseller will pay later. You can mark it paid then.</p>
        <DialogActions>
          <button type="button" className={ui.secondary} onClick={() => ask && run(ask.order, "deliver", "delivered", { paid: false })}>
            No
          </button>
          <button type="button" className={ui.primary} onClick={() => ask && run(ask.order, "deliver", "delivered", { paid: true })}>
            Yes
          </button>
        </DialogActions>
      </Dialog>
      <Dialog open={ask?.kind === "cancel"} title={`Cancel order #${ask?.order.number}?`} onClose={() => setAsk(null)}>
        <p>The reseller will see it as cancelled.</p>
        <DialogActions>
          <button type="button" className={ui.secondary} onClick={() => setAsk(null)}>
            Keep
          </button>
          <button
            type="button"
            className={`${ui.primary} ${ui.dangerPrimary}`}
            onClick={() => ask && run(ask.order, "cancel", "cancelled")}
          >
            Cancel order
          </button>
        </DialogActions>
      </Dialog>
    </>
  );

  return { buttons, dialogs };
}
