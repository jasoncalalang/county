"use client";
// Quotes: every quote of the business, newest first, filtered by status (build plan §3).
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import type { QuoteStatus, QuoteSummaryView } from "@/lib/api-types";
import { useApi } from "@/lib/dashboard/api";
import { countQuotes, QUOTE_FILTERS } from "@/lib/dashboard/quotes";
import { formatMoney, formatShortDate, manilaDate, QUOTE_STATUS_LABEL, quoteNumber } from "@/lib/format";
import { QuoteBadge } from "../_components/badges";
import { Placeholder, useShell } from "../_components/shell";
import ui from "../../ui.module.css";

export default function QuotesPage() {
  const api = useApi();
  const { toast } = useShell();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const wanted = params.get("status") ?? "";
  const filter = (QUOTE_FILTERS as string[]).includes(wanted) ? (wanted as QuoteStatus) : "all";
  const [quotes, setQuotes] = useState<QuoteSummaryView[] | null>(null);

  const load = useCallback(async () => {
    if (!api.ready) return;
    try {
      setQuotes(await api.get<QuoteSummaryView[]>("/quotes"));
    } catch {
      toast("Could not load quotes, try again", "error");
    }
  }, [api, toast]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load();
  }, [load]);

  const setFilter = (status: QuoteStatus | "all") => {
    const next = new URLSearchParams(params.toString());
    if (status === "all") next.delete("status");
    else next.set("status", status);
    router.replace(`${pathname}?${next.toString()}`, { scroll: false });
  };

  const counts = useMemo(() => countQuotes(quotes ?? []), [quotes]);
  const shown = useMemo(() => (quotes ?? []).filter((q) => filter === "all" || q.status === filter), [quotes, filter]);
  const created = (q: QuoteSummaryView) => formatShortDate(manilaDate(new Date(q.createdAt)));

  return (
    <>
      <div className={ui.pageHeader}>
        <h1 className={ui.title}>Quotes</h1>
        <Link href="/quotes/new" className={ui.primary}>
          New quote
        </Link>
      </div>

      {quotes === null ? (
        <Placeholder />
      ) : quotes.length === 0 ? (
        <div className={ui.empty}>
          <h2 className={ui.heading}>No quotes yet</h2>
          <p>Offer a reseller a set of products at today&apos;s prices. They accept it from their order link.</p>
          <Link href="/quotes/new" className={ui.primary}>
            New quote
          </Link>
        </div>
      ) : (
        <>
          <div className={ui.chips} role="group" aria-label="Filter by status">
            {QUOTE_FILTERS.map((s) => (
              <button key={s} type="button" className={ui.chip} aria-pressed={filter === s} onClick={() => setFilter(s)}>
                {s === "all" ? "All" : QUOTE_STATUS_LABEL[s]} <span className={ui.num}>{counts[s]}</span>
              </button>
            ))}
          </div>

          <div className={ui.tableWrap}>
            <table className={ui.table}>
              <thead>
                <tr>
                  <th scope="col">Quote</th>
                  <th scope="col">Reseller</th>
                  <th scope="col">Created</th>
                  <th scope="col">Accept by</th>
                  <th scope="col" className={ui.right}>
                    Total
                  </th>
                  <th scope="col">Status</th>
                </tr>
              </thead>
              <tbody>
                {shown.map((q) => (
                  <tr key={q.id}>
                    <td>
                      <Link href={`/quotes/${q.id}`}>{quoteNumber(q.number)}</Link>
                    </td>
                    <td>{q.resellerName}</td>
                    <td className={ui.small}>{created(q)}</td>
                    <td className={ui.small}>{formatShortDate(q.expiresOn)}</td>
                    <td className={ui.right}>{formatMoney(q.total)}</td>
                    <td>
                      <QuoteBadge status={q.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <ul className={ui.cards}>
            {shown.map((q) => (
              <li key={q.id}>
                <div className={ui.cardLine}>
                  <strong>
                    <Link href={`/quotes/${q.id}`}>{quoteNumber(q.number)}</Link> · {q.resellerName}
                  </strong>
                  <strong className={ui.num}>{formatMoney(q.total)}</strong>
                </div>
                <div className={ui.cardLine}>
                  <span className={ui.small}>Accept by {formatShortDate(q.expiresOn)}</span>
                  <QuoteBadge status={q.status} />
                </div>
              </li>
            ))}
          </ul>
          {shown.length === 0 && <p className={ui.small}>No {QUOTE_STATUS_LABEL[filter as QuoteStatus].toLowerCase()} quotes.</p>}
        </>
      )}
    </>
  );
}
