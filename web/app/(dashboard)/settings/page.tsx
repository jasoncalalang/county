"use client";
// 6.8 Settings. GUESS: no delivery preview yet; the API has no endpoint that answers
// "an order placed now would be delivered on", and web must not compute it.
import { useState } from "react";
import type { BusinessView } from "@/lib/api-types";
import { ApiError, useApi } from "@/lib/dashboard/api";
import { readBusinessForm, type BusinessErrors } from "@/lib/dashboard/business-form";
import { BusinessFields } from "../_components/business-fields";
import { useShell } from "../_components/shell";
import ui from "../../ui.module.css";

export default function SettingsPage() {
  const api = useApi();
  const { business, setBusiness, toast } = useShell();
  const [errors, setErrors] = useState<BusinessErrors>({});
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);

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
      if (!(error instanceof ApiError && error.status === 401)) toast("Could not save, try again", "error");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={ui.narrow}>
      <h1 className={ui.title}>Settings</h1>
      <form onSubmit={submit} noValidate>
        <BusinessFields initial={business} errors={errors} onChange={() => setSaved(false)} />
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
