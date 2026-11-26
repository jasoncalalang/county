import "next-auth";
import "next-auth/jwt";

declare module "next-auth" {
  interface Session {
    /** Google ID token, sent to the API as `Authorization: Bearer`. */
    idToken?: string;
    /** Seconds since the epoch. */
    idTokenExpiresAt?: number;
    error?: "RefreshTokenMissing" | "RefreshTokenError";
  }
}

declare module "next-auth/jwt" {
  interface JWT {
    idToken?: string;
    refreshToken?: string;
    expiresAt?: number;
    error?: "RefreshTokenMissing" | "RefreshTokenError";
  }
}
