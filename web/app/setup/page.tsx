"use client";
// 6.2 Set up your business, after the first sign-in (functional spec 3.1).
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import type { MeView } from "@/lib/api-types";
import { ApiError, useApi } from "@/lib/dashboard/api";
import { readBusinessForm, type BusinessErrors } from "@/lib/dashboard/business-form";
import { BusinessFields } from "../(dashboard)/_components/business-fields";
import ui from "../ui.module.css";

const DEFAULTS = { name: "", cutoff: "16:00", deliveryDays: [1, 2, 3, 4, 5, 6] };

export default function SetupPage() {
  const api = useApi();
  const router = useRouter();
  const [errors, setErrors] = useState<BusinessErrors>({});
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);

  // Signing in again with a business goes straight to Orders.
  useEffect(() => {
    if (!api.ready) return;
    api
      .get<MeView>("/me")
      .then((me) => me.tenant && router.replace("/orders"))
      .catch(() => undefined);
  }, [api, router]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const { input, errors: found } = readBusinessForm(new FormData(event.currentTarget));
    setErrors(found);
    setFailed(false);
    if (Object.keys(found).length) return;
    setBusy(true);
    try {
      await api.post("/tenants", input);
      router.replace("/orders");
    } catch (error) {
      // A second tab may have created the business first.
      if (error instanceof ApiError && error.status === 409) router.replace("/orders");
      else setFailed(true);
      setBusy(false);
    }
  }

  return (
    <main className={ui.main}>
      <div className={ui.narrow}>
        <h1 className={ui.title}>Set up your business</h1>
        <form onSubmit={submit} noValidate className={ui.card}>
          <BusinessFields initial={DEFAULTS} errors={errors} />
          {failed && <p className={ui.fieldError}>Could not save, try again</p>}
          <button type="submit" className={`${ui.primary} ${ui.wide}`} disabled={busy} style={{ marginTop: 16 }}>
            Create business
          </button>
        </form>
      </div>
    </main>
  );
}
