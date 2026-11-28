import type { ResellerView } from "../api-types";

export type LinkStatus = { expired: boolean; label: string };

/** Whether a reseller's order link still works at `now`, and the line the owner reads about it. */
export function linkStatus(reseller: Pick<ResellerView, "linkExpiresAt">, now: Date): LinkStatus {
  throw new Error("not implemented");
}
