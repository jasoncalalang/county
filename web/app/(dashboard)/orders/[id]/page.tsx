"use client";
// 6.4 One order: lines, note, reseller, history and the same actions as its row.
import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import type { OrderView, ResellerView } from "@/lib/api-types";
import { ApiError, useApi } from "@/lib/dashboard/api";
import { formatDayTime, formatLongDate, formatMobile, formatMoney } from "@/lib/format";
import { PaidBadge, StatusBadge } from "../../_components/badges";
import { useOrderActions } from "../../_components/order-actions";
import { Placeholder } from "../../_components/shell";
import ui from "../../../ui.module.css";

export default function OrderDetailPage() {
  const { id } = useParams<{ id: string }>();
  const api = useApi();
  const [order, setOrder] = useState<OrderView | null>(null);
  const [reseller, setReseller] = useState<ResellerView | null>(null);
  const [missing, setMissing] = useState(false);

  const load = useCallback(async () => {
    if (!api.ready) return;
    try {
      const found = await api.get<OrderView>(`/orders/${encodeURIComponent(id)}`);
      setOrder(found);
      const people = await api.get<ResellerView[]>("/resellers");
      setReseller(people.find((r) => r.id === found.resellerId) ?? null);
    } catch (error) {
      if (error instanceof ApiError && (error.status === 404 || error.status === 422)) setMissing(true);
    }
  }, [api, id]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load();
  }, [load]);

  const { buttons, dialogs } = useOrderActions(setOrder);

  if (missing) {
    return (
      <div className={ui.empty}>
        <h1 className={ui.heading}>Order not found</h1>
        <Link href="/orders" className={ui.primary}>
          Go to orders
        </Link>
      </div>
    );
  }
  if (!order) return <Placeholder />;

  const history = [
    ["Placed", order.placedAt],
    ["Confirmed", order.confirmedAt],
    ["Delivered", order.deliveredAt],
    ["Paid", order.paidAt],
    ["Cancelled", order.cancelledAt],
  ].filter((entry): entry is [string, string] => Boolean(entry[1]));

  return (
    <>
      <Link href={`/orders?date=${order.deliveryDate}`} className={ui.back}>
        ← Orders
      </Link>
      <div className={ui.pageHeader}>
        <div className={ui.badges}>
          <h1 className={ui.title}>Order #{order.number}</h1>
          <StatusBadge status={order.status} />
          <PaidBadge status={order.status} paid={order.paid} />
        </div>
        {buttons(order)}
      </div>

      <div className={ui.detail}>
        <section>
          <table className={ui.table}>
            <thead>
              <tr>
                <th scope="col">Product</th>
                <th scope="col" className={ui.right}>
                  Qty
                </th>
                <th scope="col" className={ui.right}>
                  Unit price
                </th>
                <th scope="col" className={ui.right}>
                  Line total
                </th>
              </tr>
            </thead>
            <tbody>
              {order.lines.map((line) => (
                <tr key={line.productId}>
                  <td>{line.productName}</td>
                  <td className={ui.right}>
                    {line.quantity} {line.unit}
                  </td>
                  <td className={ui.right}>{formatMoney(line.price)}</td>
                  <td className={ui.right}>{formatMoney(line.total)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <td colSpan={3}>Total</td>
                <td className={ui.right}>{formatMoney(order.total)}</td>
              </tr>
            </tfoot>
          </table>
          {order.note && <blockquote className={ui.quote}>{order.note}</blockquote>}
        </section>

        <aside className={ui.card}>
          <dl className={ui.facts}>
            <div>
              <dt>Reseller</dt>
              <dd>
                {reseller ? (
                  <>
                    <strong>{reseller.name}</strong>
                    <br />
                    <span className={ui.num}>{formatMobile(reseller.mobile)}</span>
                  </>
                ) : (
                  <strong>{order.resellerName}</strong>
                )}
              </dd>
            </div>
            <div>
              <dt>Delivery date</dt>
              <dd>{formatLongDate(order.deliveryDate)}</dd>
            </div>
            <div>
              <dt>History</dt>
              <dd>
                <ul className={ui.history}>
                  {history.map(([label, at]) => (
                    <li key={label}>
                      {label} {formatDayTime(at)}
                    </li>
                  ))}
                </ul>
              </dd>
            </div>
          </dl>
        </aside>
      </div>
      {dialogs}
    </>
  );
}
