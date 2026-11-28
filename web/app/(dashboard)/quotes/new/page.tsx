"use client";
// New quote: a reseller, quantities and the last day to accept. The API prices the lines as an
// order placed now would be and saves a draft; the owner checks it on the detail, then sends.
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import type { ProductView, QuoteView, ResellerView } from "@/lib/api-types";
import { ApiError, useApi } from "@/lib/dashboard/api";
import { apiErrors, defaultExpiry, quoteInput, type DraftErrors } from "@/lib/dashboard/quotes";
import { formatMoney, manilaDate, quoteNumber } from "@/lib/format";
import { quantityAttrs } from "@/lib/quantity";
import { Placeholder, useShell } from "../../_components/shell";
import ui from "../../../ui.module.css";

export default function NewQuotePage() {
  const api = useApi();
  const { toast } = useShell();
  const router = useRouter();
  const params = useSearchParams();
  const [resellers, setResellers] = useState<ResellerView[] | null>(null);
  const [products, setProducts] = useState<ProductView[] | null>(null);
  const [resellerId, setResellerId] = useState(params.get("resellerId") ?? "");
  const [quantities, setQuantities] = useState<Record<string, string>>({});
  const [expiresOn, setExpiresOn] = useState(() => defaultExpiry());
  const [note, setNote] = useState("");
  const [errors, setErrors] = useState<DraftErrors>({});
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    if (!api.ready) return;
    try {
      const [people, list] = await Promise.all([api.get<ResellerView[]>("/resellers"), api.get<ProductView[]>("/products")]);
      setResellers(people.filter((r) => r.status !== "blocked"));
      setProducts(list.filter((p) => p.active));
    } catch {
      toast("Could not load resellers and products, try again", "error");
    }
  }, [api, toast]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load();
  }, [load]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const units = Object.fromEntries((products ?? []).map((p) => [String(p.id), p.unit]));
    const checked = quoteInput({ resellerId, quantities, expiresOn, note }, manilaDate(new Date()), units);
    if (!checked.ok) {
      setErrors(checked.errors);
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      const quote = await api.post<QuoteView>("/quotes", checked.input);
      toast(`Quote ${quoteNumber(quote.number)} saved as a draft`);
      router.push(`/quotes/${quote.id}`);
    } catch (error) {
      if (error instanceof ApiError && Object.keys(error.fields).length) setErrors(apiErrors(error.fields, checked.input.lines));
      else if (error instanceof ApiError && error.status === 404)
        setErrors({ form: "That reseller or a product is no longer available. Reload and try again." });
      else if (!(error instanceof ApiError && error.status === 401)) setErrors({ form: "Could not save, try again" });
      setBusy(false);
    }
  }

  if (resellers === null || products === null) return <Placeholder />;

  if (resellers.length === 0 || products.length === 0) {
    return (
      <div className={ui.empty}>
        <h1 className={ui.heading}>{resellers.length === 0 ? "Add a reseller first" : "Add a product first"}</h1>
        <p>A quote offers products to one reseller.</p>
        <Link href={resellers.length === 0 ? "/resellers?add=1" : "/products"} className={ui.primary}>
          {resellers.length === 0 ? "Add reseller" : "Add product"}
        </Link>
      </div>
    );
  }

  return (
    <div className={ui.narrow}>
      <Link href="/quotes" className={ui.back}>
        ← Quotes
      </Link>
      <h1 className={ui.title}>New quote</h1>
      <form onSubmit={submit} noValidate style={{ marginTop: 16 }}>
        <div className={ui.field}>
          <label htmlFor="quote-reseller" className={ui.label}>
            Reseller
          </label>
          <select
            id="quote-reseller"
            value={resellerId}
            onChange={(e) => setResellerId(e.target.value)}
            aria-invalid={Boolean(errors.resellerId)}
          >
            <option value="">Choose a reseller</option>
            {resellers.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
          </select>
          {errors.resellerId && <span className={ui.fieldError}>{errors.resellerId}</span>}
        </div>

        <fieldset style={{ border: 0, margin: 0, padding: 0 }}>
          <legend className={ui.label}>Products</legend>
          <p className={ui.small} style={{ margin: "4px 0 8px" }}>
            Prices are set when you save, from the reseller&apos;s price tier if they have one. You check them before
            sending.
          </p>
          <div className={ui.lineRows}>
            {products.map((p) => {
              const error = errors.quantities?.[String(p.id)];
              const attrs = quantityAttrs(p.unit);
              return (
                <div key={p.id} className={ui.lineRow}>
                  <span>
                    <label htmlFor={`qty-${p.id}`}>
                      <strong>{p.name}</strong>
                    </label>
                    <br />
                    <span className={`${ui.small} ${ui.num}`}>
                      {p.unit} · {formatMoney(p.price)}
                    </span>
                    {error && (
                      <>
                        <br />
                        <span className={ui.fieldError}>{error}</span>
                      </>
                    )}
                  </span>
                  <input
                    id={`qty-${p.id}`}
                    type="number"
                    inputMode={attrs.inputMode}
                    min={0}
                    max={9999}
                    step={attrs.step}
                    placeholder="0"
                    aria-label={`Quantity of ${p.name}`}
                    aria-invalid={Boolean(error)}
                    value={quantities[String(p.id)] ?? ""}
                    onChange={(e) => setQuantities((all) => ({ ...all, [String(p.id)]: e.target.value }))}
                  />
                </div>
              );
            })}
          </div>
          {errors.lines && (
            <p className={ui.fieldError} role="alert" style={{ marginTop: -8, marginBottom: 16 }}>
              {errors.lines}
            </p>
          )}
        </fieldset>

        <div className={ui.field}>
          <label htmlFor="quote-expires" className={ui.label}>
            Last day to accept
          </label>
          <input
            id="quote-expires"
            type="date"
            value={expiresOn}
            min={manilaDate(new Date())}
            onChange={(e) => setExpiresOn(e.target.value)}
            aria-invalid={Boolean(errors.expiresOn)}
          />
          {errors.expiresOn && <span className={ui.fieldError}>{errors.expiresOn}</span>}
        </div>

        <div className={ui.field}>
          <label htmlFor="quote-note" className={ui.label}>
            Note for the reseller (optional)
          </label>
          <textarea
            id="quote-note"
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
        <div className={ui.dialogActions}>
          <Link href="/quotes" className={ui.secondary}>
            Cancel
          </Link>
          <button type="submit" className={ui.primary} disabled={busy}>
            Save draft
          </button>
        </div>
      </form>
    </div>
  );
}
