"use client";
// The dashboard frame: app bar, navigation with the placed-order count, toasts, and the
// business every page works on. A signed-in user without a business is sent to /setup.
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { signOut } from "next-auth/react";
import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import type { BusinessView, MeView, OrderSummaryView } from "@/lib/api-types";
import { useApi } from "@/lib/dashboard/api";
import ui from "../../ui.module.css";

const POLL_MS = 30_000;

type ToastKind = "info" | "error";

interface ShellValue {
  business: BusinessView;
  setBusiness: (business: BusinessView) => void;
  refreshPlaced: () => void;
  toast: (message: string, kind?: ToastKind) => void;
}

const ShellContext = createContext<ShellValue | null>(null);

export function useShell(): ShellValue {
  const value = useContext(ShellContext);
  if (!value) throw new Error("useShell outside the dashboard");
  return value;
}

const NAV = [
  { href: "/orders", label: "Orders" },
  { href: "/quotes", label: "Quotes" },
  { href: "/production-run", label: "Production run" },
  { href: "/resellers", label: "Resellers" },
  { href: "/products", label: "Products" },
  { href: "/settings", label: "Settings" },
];

export function Shell({ children }: { children: React.ReactNode }) {
  const api = useApi();
  const router = useRouter();
  const pathname = usePathname();
  const [business, setBusiness] = useState<BusinessView | null>(null);
  const [placed, setPlaced] = useState(0);
  const [toasts, setToasts] = useState<{ id: number; message: string; kind: ToastKind }[]>([]);
  const nextToast = useRef(0);

  useEffect(() => {
    if (!api.ready) return;
    let live = true;
    api
      .get<MeView>("/me")
      .then((me) => {
        if (!live) return;
        if (me.tenant) setBusiness(me.tenant);
        else router.replace("/setup");
      })
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [api, router]);

  const refreshPlaced = useCallback(() => {
    if (!api.ready) return;
    api
      .get<OrderSummaryView[]>("/orders?status=placed")
      .then((orders) => setPlaced(orders.length))
      .catch(() => undefined);
  }, [api]);

  useEffect(() => {
    if (!business) return;
    refreshPlaced();
    const timer = setInterval(refreshPlaced, POLL_MS);
    return () => clearInterval(timer);
  }, [business, refreshPlaced]);

  const toast = useCallback((message: string, kind: ToastKind = "info") => {
    const id = (nextToast.current += 1);
    setToasts((all) => [...all, { id, message, kind }]);
    setTimeout(() => setToasts((all) => all.filter((t) => t.id !== id)), 4000);
  }, []);

  return (
    <>
      <header className={ui.appBar}>
        <div className={ui.appBarInner}>
          <span className={ui.business}>{business?.name ?? "county"}</span>
          <nav className={ui.nav} aria-label="Dashboard">
            {NAV.map((item) => {
              const current = pathname === item.href || pathname.startsWith(`${item.href}/`);
              const showCount = item.href === "/orders" && placed > 0;
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  className={ui.navLink}
                  aria-current={current ? "page" : undefined}
                  aria-label={showCount ? `Orders, ${placed} to confirm` : undefined}
                >
                  {item.label}
                  {showCount && <span className={ui.count}>{placed}</span>}
                </Link>
              );
            })}
          </nav>
          <button type="button" className={ui.textButton} onClick={() => signOut({ redirectTo: "/" })}>
            Sign out
          </button>
        </div>
      </header>
      <main className={ui.main}>
        {business ? (
          <ShellContext.Provider value={{ business, setBusiness, refreshPlaced, toast }}>{children}</ShellContext.Provider>
        ) : (
          <Placeholder />
        )}
      </main>
      <div className={ui.toasts} role="status" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={`${ui.toast} ${t.kind === "error" ? ui.toastError : ""}`}>
            {t.message}
          </div>
        ))}
      </div>
    </>
  );
}

/** Three grey rows for a first load only (UI design, section 8). */
export function Placeholder() {
  return (
    <div aria-hidden="true">
      <div className={ui.placeholderRow} />
      <div className={ui.placeholderRow} />
      <div className={ui.placeholderRow} />
    </div>
  );
}
