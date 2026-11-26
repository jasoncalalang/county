"use client";
// 6.5 Production run: what the kitchen makes for one delivery date, from confirmed orders.
import Link from "next/link";
import { useEffect, useState } from "react";
import type { OrderSummaryView, ProductionRunView } from "@/lib/api-types";
import { useApi } from "@/lib/dashboard/api";
import { formatLongDate, formatMoney, formatShortDate } from "@/lib/format";
import { DatePicker } from "../_components/date-picker";
import { Placeholder, useShell } from "../_components/shell";
import { useDeliveryDate } from "../_components/use-delivery-date";
import ui from "../../ui.module.css";

export default function ProductionRunPage() {
  const api = useApi();
  const { business, toast } = useShell();
  const [date, setDate] = useDeliveryDate();
  const [run, setRun] = useState<ProductionRunView | null>(null);
  const [confirmed, setConfirmed] = useState<OrderSummaryView[]>([]);

  useEffect(() => {
    if (!date || !api.ready) return;
    let live = true;
    Promise.all([
      api.get<ProductionRunView>(`/production-run?date=${date}`),
      api.get<OrderSummaryView[]>(`/orders?date=${date}&status=confirmed`),
    ])
      .then(([r, c]) => {
        if (!live) return;
        setRun(r);
        setConfirmed(c);
      })
      .catch(() => toast("Could not load the production run, try again", "error"));
    return () => {
      live = false;
    };
  }, [api, date, toast]);

  const total = confirmed.reduce((sum, o) => sum + Math.round(o.total * 100), 0) / 100;
  const unconfirmed = run?.unconfirmedCount ?? 0;

  return (
    <>
      <p className={ui.printHeader}>
        {business.name} · Production run · {date && formatLongDate(date)}
      </p>
      <div className={`${ui.pageHeader} ${ui.noPrint}`}>
        <h1 className={ui.title}>Production run</h1>
        <button type="button" className={ui.secondary} onClick={() => window.print()}>
          Print
        </button>
      </div>
      <div className={`${ui.controls} ${ui.noPrint}`}>
        {date && <DatePicker date={date} deliveryDays={business.deliveryDays} onChange={setDate} />}
      </div>

      {!run || run.date !== date ? (
        <Placeholder />
      ) : (
        <>
          {unconfirmed > 0 && (
            <p className={`${ui.notice} ${ui.noPrint}`}>
              <Link href={`/orders?date=${date}&status=placed`}>
                {unconfirmed} {unconfirmed === 1 ? "order is" : "orders are"} not yet confirmed and{" "}
                {unconfirmed === 1 ? "is" : "are"} not included.
              </Link>
            </p>
          )}
          {run.lines.length === 0 ? (
            <div className={ui.empty}>
              <h2 className={ui.heading}>Nothing to make for {formatShortDate(run.date)}</h2>
              <p>Confirm orders to see them here.</p>
              <Link href={`/orders?date=${run.date}`} className={`${ui.primary} ${ui.noPrint}`}>
                Go to orders
              </Link>
            </div>
          ) : (
            <>
              <table className={ui.table}>
                <thead>
                  <tr>
                    <th scope="col">Product</th>
                    <th scope="col">Unit</th>
                    <th scope="col" className={ui.right}>
                      Quantity
                    </th>
                    <th scope="col" className={ui.right}>
                      Orders
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {run.lines.map((line) => (
                    <tr key={line.productId}>
                      <td>{line.productName}</td>
                      <td>{line.unit}</td>
                      <td className={ui.right}>{line.quantity}</td>
                      <td className={ui.right}>{line.orderCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <p className={`${ui.small} ${ui.freshness}`}>
                {confirmed.length} confirmed {confirmed.length === 1 ? "order" : "orders"} · {formatMoney(total)}
              </p>
            </>
          )}
        </>
      )}
    </>
  );
}
