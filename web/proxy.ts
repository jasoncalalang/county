// Sends signed-out visitors of a dashboard URL to the sign-in page, and signed-in visitors of the
// sign-in page to Orders. Whether the user has a business yet is checked by the dashboard
// against GET /api/me, because only the API knows.
import { NextResponse } from "next/server";
import { auth } from "@/auth";

const DASHBOARD = ["/setup", "/orders", "/quotes", "/production-run", "/resellers", "/products", "/settings"];

export default auth((request) => {
  const { pathname } = request.nextUrl;
  const signedIn = Boolean(request.auth?.idToken) && !request.auth?.error;
  if (pathname === "/") {
    return signedIn ? NextResponse.redirect(new URL("/orders", request.nextUrl)) : NextResponse.next();
  }
  if (!signedIn && DASHBOARD.some((p) => pathname === p || pathname.startsWith(`${p}/`))) {
    const url = new URL("/", request.nextUrl);
    if (request.auth?.error) url.searchParams.set("reason", "expired");
    return NextResponse.redirect(url);
  }
  return NextResponse.next();
});

export const config = {
  matcher: ["/", "/setup", "/orders/:path*", "/quotes/:path*", "/production-run", "/resellers", "/products", "/settings"],
};
