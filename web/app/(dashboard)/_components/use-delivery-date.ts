"use client";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import type { OrderSummaryView } from "@/lib/api-types";
import { useApi } from "@/lib/dashboard/api";
import { defaultDeliveryDate } from "@/lib/dashboard/orders";
import { useShell } from "./shell";

/** The page's delivery date: `?date=` when given, else the default of functional spec 3.4. */
export function useDeliveryDate(): [string | null, (date: string) => void] {
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const api = useApi();
  const { business } = useShell();
  const fromUrl = params.get("date");
  const [fallback, setFallback] = useState<string | null>(null);

  useEffect(() => {
    if (fromUrl || fallback || !api.ready) return;
    Promise.all([
      api.get<OrderSummaryView[]>("/orders?status=placed"),
      api.get<OrderSummaryView[]>("/orders?status=confirmed"),
    ])
      .then(([placed, confirmed]) => setFallback(defaultDeliveryDate([...placed, ...confirmed], business.deliveryDays)))
      .catch(() => setFallback(defaultDeliveryDate([], business.deliveryDays)));
  }, [api, business.deliveryDays, fallback, fromUrl]);

  const setDate = useCallback(
    (date: string) => {
      const next = new URLSearchParams(params.toString());
      next.set("date", date);
      router.replace(`${pathname}?${next.toString()}`, { scroll: false });
    },
    [params, pathname, router],
  );

  return [fromUrl && /^\d{4}-\d{2}-\d{2}$/.test(fromUrl) ? fromUrl : fallback, setDate];
}
