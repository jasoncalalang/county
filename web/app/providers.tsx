"use client";
import { SessionProvider } from "next-auth/react";

// Auth.js lives at /auth, not /api/auth (ADR 0010). Refetching every four minutes keeps the
// Google ID token in the session ahead of its expiry; the jwt callback refreshes it.
export function Providers({ children }: { children: React.ReactNode }) {
  return (
    <SessionProvider basePath="/auth" refetchInterval={240} refetchOnWindowFocus>
      {children}
    </SessionProvider>
  );
}
