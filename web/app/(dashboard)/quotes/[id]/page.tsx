"use client";
// One quote: its captured lines, the reseller, and send, share or cancel. A sent quote shows on
// the reseller's order link, where they accept it into an order or decline it.
import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import type { QuoteView, ResellerView } from "@/lib/api-types";
import { ApiError, useApi } from "@/lib/dashboard/api";
import { quoteHistory, quoteLink, quoteShareMessage } from "@/lib/dashboard/quotes";
import { formatDayTime, formatLongDate, formatMobile, formatMoney, quoteNumber } from "@/lib/format";
import { QuoteBadge } from "../../_components/badges";
import { Dialog, DialogActions } from "../../_components/dialog";
import { Placeholder, useShell } from "../../_components/shell";
import ui from "../../../ui.module.css";

type Modal = "share" | "cancel" | null;

export default function QuoteDetailPage() {
  const { id } = useParams<{ id: string }>();
  const api = useApi();
  const { business, toast } = useShell();
  const [quote, setQuote] = useState<QuoteView | null>(null);
  const [reseller, setReseller] = useState<ResellerView | null>(null);
  const [missing, setMissing] = useState(false);
  const [modal, setModal] = useState<Modal>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    if (!api.ready) return;
    try {
      const found = await api.get<QuoteView>(`/quotes/${encodeURIComponent(id)}`);
      setQuote(found);
      const people = await api.get<ResellerView[]>("/resellers");
      setReseller(people.find((r) => r.id === found.resellerId) ?? null);
    } catch (error) {
      if (error instanceof ApiError && (error.status === 404 || error.status === 422)) setMissing(true);
      else toast("Could not load the quote, try again", "error");
    }
  }, [api, id, toast]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load();
  }, [load]);

  async function run(action: "send" | "cancel") {
    if (!quote) return;
    setBusy(true);
    setModal(null);
    try {
      const updated = await api.post<QuoteView>(`/quotes/${quote.id}/${action}`);
      setQuote(updated);
      if (action === "send") {
        toast(`Quote ${quoteNumber(updated.number)} sent`);
        setModal("share");
      } else toast(`Quote ${quoteNumber(updated.number)} cancelled`);
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        try {
          const now = await api.get<QuoteView>(`/quotes/${quote.id}`);
          setQuote(now);
          toast(now.status === quote.status ? error.message : `This quote is now ${now.status}`, "error");
        } catch {
          toast("Could not save, try again", "error");
        }
      } else if (!(error instanceof ApiError && error.status === 401)) {
        toast("Could not save, try again", "error");
      }
    } finally {
      setBusy(false);
    }
  }

  async function copy(text: string, what: string) {
    try {
      await navigator.clipboard.writeText(text);
      toast(`${what} copied`);
    } catch {
      toast("Could not copy. Select the link and copy it.", "error");
    }
  }

  if (missing) {
    return (
      <div className={ui.empty}>
        <h1 className={ui.heading}>Quote not found</h1>
        <Link href="/quotes" className={ui.primary}>
          Go to quotes
        </Link>
      </div>
    );
  }
  if (!quote) return <Placeholder />;

  const label = quoteNumber(quote.number);
  const open = quote.status === "draft" || quote.status === "sent";
  const link = reseller ? quoteLink(reseller.orderLink, quote.id) : null;

  const actions = busy ? (
    <span className={ui.small} role="status">
      Saving…
    </span>
  ) : open ? (
    <div className={ui.actions}>
      <button type="button" className={ui.dangerButton} onClick={() => setModal("cancel")}>
        Cancel quote
      </button>
      {quote.status === "draft" ? (
        <button type="button" className={ui.primary} onClick={() => run("send")}>
          Send
        </button>
      ) : (
        link && (
          <button type="button" className={ui.secondary} onClick={() => setModal("share")}>
            Share link
          </button>
        )
      )}
    </div>
  ) : quote.acceptedOrderId ? (
    <Link href={`/orders/${quote.acceptedOrderId}`} className={ui.secondary}>
      View order
    </Link>
  ) : null;

  return (
    <>
      <Link href="/quotes" className={ui.back}>
        ← Quotes
      </Link>
      <div className={ui.pageHeader}>
        <div className={ui.badges}>
          <h1 className={ui.title}>Quote {label}</h1>
          <QuoteBadge status={quote.status} />
        </div>
        {actions}
      </div>
      {quote.status === "draft" && (
        <p className={ui.notice}>Check the prices, then send. Only sent quotes show on the reseller&apos;s order link.</p>
      )}

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
              {quote.lines.map((line) => (
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
                <td className={ui.right}>{formatMoney(quote.total)}</td>
              </tr>
            </tfoot>
          </table>
          {quote.note && <blockquote className={ui.quote}>{quote.note}</blockquote>}
        </section>

        <aside className={ui.card}>
          <dl className={ui.facts}>
            <div>
              <dt>Reseller</dt>
              <dd>
                <strong>{reseller?.name ?? quote.resellerName}</strong>
                {reseller && (
                  <>
                    <br />
                    <span className={ui.num}>{formatMobile(reseller.mobile)}</span>
                  </>
                )}
              </dd>
            </div>
            <div>
              <dt>Last day to accept</dt>
              <dd>{formatLongDate(quote.expiresOn)}</dd>
            </div>
            <div>
              <dt>Estimated delivery</dt>
              <dd>
                {formatLongDate(quote.deliveryDate)}
                <br />
                <span className={ui.small}>The order gets its delivery date when the reseller accepts.</span>
              </dd>
            </div>
            <div>
              <dt>History</dt>
              <dd>
                <ul className={ui.history}>
                  {quoteHistory(quote).map(([what, at]) => (
                    <li key={what}>
                      {what} {formatDayTime(at)}
                    </li>
                  ))}
                </ul>
              </dd>
            </div>
          </dl>
        </aside>
      </div>

      <Dialog open={modal === "share"} title={`Send quote ${label} to ${reseller?.name ?? quote.resellerName}`} onClose={() => setModal(null)}>
        {link && reseller && (
          <>
            <p className={ui.small}>
              It opens on {reseller.name}&apos;s order link, where they can accept it until{" "}
              {formatLongDate(quote.expiresOn)}.
            </p>
            <div className={ui.linkBox}>{link}</div>
            <div className={ui.actions} style={{ justifyContent: "flex-start" }}>
              <button type="button" className={ui.secondary} onClick={() => copy(link, "Link")}>
                Copy link
              </button>
              <button
                type="button"
                className={ui.secondary}
                onClick={() => copy(quoteShareMessage(reseller, business.name, quote), "Message")}
              >
                Copy message
              </button>
            </div>
          </>
        )}
        <DialogActions>
          <button type="button" className={ui.primary} onClick={() => setModal(null)}>
            Done
          </button>
        </DialogActions>
      </Dialog>

      <Dialog open={modal === "cancel"} title={`Cancel quote ${label}?`} onClose={() => setModal(null)}>
        <p>{quote.status === "sent" ? "The reseller can no longer accept it." : "It will not be sent."}</p>
        <DialogActions>
          <button type="button" className={ui.secondary} onClick={() => setModal(null)}>
            Keep
          </button>
          <button type="button" className={`${ui.primary} ${ui.dangerPrimary}`} onClick={() => run("cancel")}>
            Cancel quote
          </button>
        </DialogActions>
      </Dialog>
    </>
  );
}
