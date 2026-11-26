"use client";
// 6.3 Orders for one delivery date (UI design; functional spec 3.4).
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import type { OrderSummaryView, OrderView, ResellerView } from "@/lib/api-types";
import { useApi } from "@/lib/dashboard/api";
import { countByStatus, itemsSummary, sortOrders, summarize } from "@/lib/dashboard/orders";
import { formatClock, formatMoney, formatPlaced, formatShortDate, STATUS_LABEL, type Status } from "@/lib/format";
import { PaidBadge, StatusBadge } from "../_components/badges";
import { DatePicker } from "../_components/date-picker";
import { Freshness } from "../_components/freshness";
import { useOrderActions } from "../_components/order-actions";
import { Placeholder, useShell } from "../_components/shell";
import { useDeliveryDate } from "../_components/use-delivery-date";
import ui from "../../ui.module.css";

const POLL_MS = 30_000;
const FILTERS: (Status | "all")[] = ["all", "placed", "confirmed", "delivered", "cancelled"];

export default function OrdersPage() {
  const api = useApi();
  const { business } = useShell();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const [date, setDate] = useDeliveryDate();
  const filter = (FILTERS as string[]).includes(params.get("status") ?? "") ? (params.get("status") as Status) : "all";

  const [loaded, setLoaded] = useState<{ date: string; list: OrderSummaryView[] } | null>(null);
  const orders = loaded && loaded.date === date ? loaded.list : null;
  // Only an empty list needs the resellers: it tells a new business from a quiet day.
  const [hasResellers, setHasResellers] = useState(true);
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);
  const [failures, setFailures] = useState(0);

  const load = useCallback(async () => {
    if (!date || !api.ready) return;
    try {
      const list = await api.get<OrderSummaryView[]>(`/orders?date=${date}`);
      if (list.length === 0) setHasResellers((await api.get<ResellerView[]>("/resellers")).length > 0);
      setLoaded({ date, list });
      setUpdatedAt(Date.now());
      setFailures(0);
    } catch {
      setFailures((n) => n + 1);
    }
  }, [api, date]);

  useEffect(() => {
    // A fetch never clears the table; rows are swapped when the response arrives.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load();
    const timer = setInterval(load, POLL_MS);
    return () => clearInterval(timer);
  }, [load]);

  const onUpdated = useCallback(
    (updated: OrderView) => {
      setLoaded(
        (all) =>
          all && {
            ...all,
            list: all.list.map((o) => (o.id === updated.id ? { ...o, ...updated, items: o.items } : o)),
          },
      );
      load();
    },
    [load],
  );
  const { buttons, dialogs } = useOrderActions(onUpdated);

  const setFilter = (status: Status | "all") => {
    const next = new URLSearchParams(params.toString());
    if (status === "all") next.delete("status");
    else next.set("status", status);
    router.replace(`${pathname}?${next.toString()}`, { scroll: false });
  };

  const counts = useMemo(() => countByStatus(orders ?? []), [orders]);
  const summary = useMemo(() => summarize(orders ?? []), [orders]);
  const shown = useMemo(
    () => sortOrders((orders ?? []).filter((o) => filter === "all" || o.status === filter)),
    [orders, filter],
  );

  return (
    <>
      <h1 className={ui.title}>Orders</h1>
      <div className={ui.controls}>
        {date && <DatePicker date={date} deliveryDays={business.deliveryDays} onChange={setDate} />}
      </div>

      {orders === null ? (
        <Placeholder />
      ) : orders.length === 0 ? (
        !hasResellers ? (
          <div className={ui.empty}>
            <h2 className={ui.heading}>No orders yet</h2>
            <p>Add a reseller and send them their link.</p>
            <Link href="/resellers?add=1" className={ui.primary}>
              Add reseller
            </Link>
          </div>
        ) : (
          <div className={ui.empty}>
            <h2 className={ui.heading}>No orders for {date && formatShortDate(date)}</h2>
            <p>Orders placed before {formatClock(business.cutoff)} today will show here.</p>
          </div>
        )
      ) : (
        <>
          <div className={ui.chips} role="group" aria-label="Filter by status">
            {FILTERS.map((s) => (
              <button key={s} type="button" className={ui.chip} aria-pressed={filter === s} onClick={() => setFilter(s)}>
                {s === "all" ? "All" : STATUS_LABEL[s]} <span className={ui.num}>{counts[s]}</span>
              </button>
            ))}
          </div>
          <p className={`${ui.small} ${ui.summary}`}>
            {summary.main}
            {summary.warning && (
              <>
                {" · "}
                <span className={ui.warnText}>{summary.warning}</span>
              </>
            )}
          </p>

          <div className={ui.tableWrap}>
            <table className={ui.table}>
              <thead>
                <tr>
                  <th scope="col">#</th>
                  <th scope="col">Reseller</th>
                  <th scope="col">Placed</th>
                  <th scope="col">Items</th>
                  <th scope="col" className={ui.right}>
                    Total
                  </th>
                  <th scope="col">Status</th>
                  <th scope="col">Paid</th>
                  <th scope="col">
                    <span className="visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {shown.map((o) => (
                  <tr key={o.id}>
                    <td>
                      <Link href={`/orders/${o.id}`}>#{o.number}</Link>
                    </td>
                    <td>{o.resellerName}</td>
                    <td className={ui.small}>{formatPlaced(o.placedAt)}</td>
                    <td className={`${ui.small} ${ui.items}`} title={itemsSummary(o.items)}>
                      {itemsSummary(o.items)}
                    </td>
                    <td className={ui.right}>{formatMoney(o.total)}</td>
                    <td>
                      <StatusBadge status={o.status} />
                    </td>
                    <td>
                      <PaidBadge status={o.status} paid={o.paid} />
                    </td>
                    <td>{buttons(o)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <ul className={ui.cards}>
            {shown.map((o) => (
              <li key={o.id}>
                <div className={ui.cardLine}>
                  <strong>
                    <Link href={`/orders/${o.id}`}>#{o.number}</Link> · {o.resellerName}
                  </strong>
                  <strong className={ui.num}>{formatMoney(o.total)}</strong>
                </div>
                <div className={`${ui.small} ${ui.items}`}>{itemsSummary(o.items)}</div>
                <div className={ui.cardLine}>
                  <span className={ui.badges}>
                    <StatusBadge status={o.status} />
                    <PaidBadge status={o.status} paid={o.paid} />
                  </span>
                  {buttons(o)}
                </div>
              </li>
            ))}
          </ul>
          {shown.length === 0 && <p className={ui.small}>No {filter} orders for this date.</p>}
        </>
      )}
      <Freshness updatedAt={updatedAt} failures={failures} />
      {dialogs}
    </>
  );
}
