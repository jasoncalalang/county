"use client";
// The dashboard's calls to the API: from the browser, at the relative URL /api, with the Google
// ID token as the bearer token (ADR 0010). One retry after refreshing the session on a 401;
// if that fails too, back to the sign-in screen with "Please sign in again" (UI design, 8).
import { getSession, signOut, useSession } from "next-auth/react";
import { useCallback, useMemo } from "react";
import type { Problem } from "../api-types";

export class ApiError extends Error {
  constructor(readonly problem: Problem) {
    super(problem.detail ?? problem.title ?? `HTTP ${problem.status}`);
  }
  get status() {
    return this.problem.status;
  }
}

type Method = "GET" | "POST" | "PUT";

export async function request(token: string | undefined, method: Method, path: string, body?: unknown): Promise<Response> {
  return fetch(`/api${path}`, {
    method,
    cache: "no-store",
    headers: {
      accept: "application/json",
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(body === undefined ? {} : { "content-type": "application/json" }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

async function parse<T>(response: Response): Promise<T> {
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new ApiError({ status: response.status, ...body });
  return body as T;
}

export function useApi() {
  const { data } = useSession();
  const token = data?.idToken;

  const call = useCallback(
    async <T,>(method: Method, path: string, body?: unknown): Promise<T> => {
      let response = await request(token, method, path, body);
      if (response.status === 401) {
        const fresh = await getSession();
        if (fresh?.idToken && !fresh.error) response = await request(fresh.idToken, method, path, body);
        if (response.status === 401) {
          await signOut({ redirectTo: "/?reason=expired" });
          throw new ApiError({ status: 401, detail: "Please sign in again" });
        }
      }
      return parse<T>(response);
    },
    [token],
  );

  return useMemo(
    () => ({
      ready: Boolean(token),
      get: <T,>(path: string) => call<T>("GET", path),
      post: <T,>(path: string, body?: unknown) => call<T>("POST", path, body),
      put: <T,>(path: string, body: unknown) => call<T>("PUT", path, body),
    }),
    [call, token],
  );
}

export type Api = ReturnType<typeof useApi>;
