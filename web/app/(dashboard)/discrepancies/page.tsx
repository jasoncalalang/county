"use client";
// Delivery reports: the open ones, newest first, each linking to its order, where the owner
// answers a report the reseller filed.
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import type { DiscrepancySummaryView } from "@/lib/api-types";
import { useApi } from "@/lib/dashboard/api";
import { partyLabel, shortfallText } from "@/lib/discrepancy";
import { formatShortDate, manilaDate } from "@/lib/format";
import { Placeholder, useShell } from "../_components/shell";
import ui from "../../ui.module.css";

export default function DiscrepanciesPage() {
  const api = useApi();
  const { toast } = useShell();
  const [reports, setReports] = useState<DiscrepancySummaryView[] | null>(null);

  const load = useCallback(async () => {
    if (!api.ready) return;
    try {
      setReports(await api.get<DiscrepancySummaryView[]>("/discrepancies?status=open"));
    } catch {
      toast("Could not load delivery reports, try again", "error");
    }
  }, [api, toast]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load();
  }, [load]);

  const filed = (r: DiscrepancySummaryView) => formatShortDate(manilaDate(new Date(r.filedAt)));

  return (
    <>
      <div className={ui.pageHeader}>
        <h1 className={ui.title}>Delivery reports</h1>
      </div>

      {reports === null ? (
        <Placeholder />
      ) : reports.length === 0 ? (
        <div className={ui.empty}>
          <h2 className={ui.heading}>No open reports</h2>
          <p>When a reseller reports a problem with a delivery, it shows here until someone answers it.</p>
        </div>
      ) : (
        <>
          <div className={ui.tableWrap}>
            <table className={ui.table}>
              <thead>
                <tr>
                  <th scope="col">Order</th>
                  <th scope="col">Reseller</th>
                  <th scope="col">Reported by</th>
                  <th scope="col" className={ui.right}>
                    Difference
                  </th>
                  <th scope="col">Filed</th>
                </tr>
              </thead>
              <tbody>
                {reports.map((r) => (
                  <tr key={r.id}>
                    <td>
                      <Link href={`/orders/${r.orderId}`}>#{r.orderNumber}</Link>
                    </td>
                    <td>{r.resellerName}</td>
                    <td>{partyLabel(r.filedBy, "owner")}</td>
                    <td className={ui.right}>{shortfallText(r.shortfallTotal)}</td>
                    <td className={ui.small}>{filed(r)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <ul className={ui.cards}>
            {reports.map((r) => (
              <li key={r.id}>
                <div className={ui.cardLine}>
                  <strong>
                    <Link href={`/orders/${r.orderId}`}>#{r.orderNumber}</Link> · {r.resellerName}
                  </strong>
                  <strong className={ui.num}>{shortfallText(r.shortfallTotal)}</strong>
                </div>
                <div className={ui.cardLine}>
                  <span className={ui.small}>
                    {partyLabel(r.filedBy, "owner")} reported this · {filed(r)}
                  </span>
                </div>
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  );
}
