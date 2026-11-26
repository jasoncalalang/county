// Wholesaler sign-in with Google through Auth.js (ADR 0002), mounted at /auth (ADR 0010).
// The dashboard calls the API from the browser with the Google ID token as a bearer token, so
// the session carries the ID token and refreshes it before Google's one-hour expiry.
import NextAuth from "next-auth";
import Google from "next-auth/providers/google";

const REFRESH_MARGIN_SECONDS = 120;

async function refreshIdToken(refreshToken: string) {
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: process.env.AUTH_GOOGLE_ID ?? "",
      client_secret: process.env.AUTH_GOOGLE_SECRET ?? "",
      grant_type: "refresh_token",
      refresh_token: refreshToken,
    }),
    cache: "no-store",
  });
  if (!response.ok) throw new Error(`Google token refresh failed: ${response.status}`);
  return (await response.json()) as { id_token?: string; expires_in: number; refresh_token?: string };
}

export const { handlers, auth, signIn, signOut } = NextAuth({
  basePath: "/auth",
  trustHost: true,
  session: { strategy: "jwt" },
  pages: { signIn: "/", error: "/" },
  providers: [
    Google({
      // offline + consent so Google returns a refresh token on every sign-in, not only the first.
      authorization: { params: { access_type: "offline", prompt: "consent", scope: "openid email profile" } },
    }),
  ],
  callbacks: {
    async jwt({ token, account }) {
      if (account) {
        return {
          ...token,
          idToken: account.id_token,
          refreshToken: account.refresh_token,
          expiresAt: account.expires_at,
          error: undefined,
        };
      }
      const now = Math.floor(Date.now() / 1000);
      if (token.expiresAt && now < token.expiresAt - REFRESH_MARGIN_SECONDS) return token;
      if (!token.refreshToken) return { ...token, error: "RefreshTokenMissing" };
      try {
        const fresh = await refreshIdToken(token.refreshToken);
        return {
          ...token,
          idToken: fresh.id_token ?? token.idToken,
          expiresAt: now + fresh.expires_in,
          refreshToken: fresh.refresh_token ?? token.refreshToken,
          error: undefined,
        };
      } catch {
        return { ...token, error: "RefreshTokenError" };
      }
    },
    session({ session, token }) {
      return { ...session, idToken: token.idToken, idTokenExpiresAt: token.expiresAt, error: token.error };
    },
  },
});
