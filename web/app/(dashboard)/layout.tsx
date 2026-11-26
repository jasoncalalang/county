import { Suspense } from "react";
import { Shell } from "./_components/shell";

// The dashboard is client-rendered (ADR 0010): the browser calls /api with the Google ID token.
export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  return (
    <Suspense>
      <Shell>{children}</Shell>
    </Suspense>
  );
}
