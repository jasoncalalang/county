"use client";
// 6.8 Settings, with the delivery preview asked of the API as the fields change.
import { useCallback, useEffect, useRef, useState } from "react";
import type { BusinessView, SettingsPreviewView } from "@/lib/api-types";
import { ApiError, useApi } from "@/lib/dashboard/api";
import { readBusinessForm, type BusinessErrors } from "@/lib/dashboard/business-form";
import { previewQuery } from "@/lib/dashboard/orders";
import { formatShortDate } from "@/lib/format";
import { BusinessFields } from "../_components/business-fields";
import { useShell } from "../_components/shell";
import ui from "../../ui.module.css";

const PREVIEW_DELAY_MS = 300;

export default function SettingsPage() {
  const api = useApi();
  const { business, setBusiness, toast } = useShell();
  const form = useRef<HTMLFormElement>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [errors, setErrors] = useState<BusinessErrors>({});
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const [preview, setPreview] = useState<string | null>(null);

  const askPreview = useCallback(() => {
    if (!form.current || !api.ready) return;
    const { input, errors: found } = readBusinessForm(new FormData(form.current));
    if (found.cutoff || found.deliveryDays) {
      setPreview(null);
      return;
    }
    api
      .get<SettingsPreviewView>(`/settings/preview?${previewQuery(input.cutoff, input.deliveryDays)}`)
      .then((p) => setPreview(p.deliveryDate))
      .catch(() => setPreview(null));
  }, [api]);

  useEffect(() => {
    askPreview();
    return () => {
      if (timer.current) clearTimeout(timer.current);
    };
  }, [askPreview]);

  function changed() {
    setSaved(false);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(askPreview, PREVIEW_DELAY_MS);
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const { input, errors: found } = readBusinessForm(new FormData(event.currentTarget));
    setErrors(found);
    if (Object.keys(found).length) return;
    setBusy(true);
    try {
      setBusiness(await api.put<BusinessView>("/settings", input));
      setSaved(true);
      setTimeout(() => setSaved(false), 4000);
    } catch (error) {
      if (error instanceof ApiError && Object.keys(error.fields).length) setErrors(error.fields);
      else if (!(error instanceof ApiError && error.status === 401)) toast("Could not save, try again", "error");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={ui.narrow}>
      <h1 className={ui.title}>Settings</h1>
      <form ref={form} onSubmit={submit} noValidate>
        <BusinessFields initial={business} errors={errors} onChange={changed} />
        <p aria-live="polite">
          {preview ? `An order placed now would be delivered on ${formatShortDate(preview)}.` : "\u00a0"}
        </p>
        <p className={ui.small}>Changes apply to orders placed after you save. Existing orders keep their delivery date.</p>
        <div className={ui.controls} style={{ marginTop: 16 }}>
          <button type="submit" className={ui.primary} disabled={busy}>
            Save
          </button>
          {saved && (
            <span className={ui.small} role="status">
              Saved
            </span>
          )}
        </div>
      </form>
    </div>
  );
}
