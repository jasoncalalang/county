import type { ResellerView } from "../api-types";
import { TIME_ZONE } from "../format";

export type LinkStatus = { expired: boolean; label: string };

const day = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", year: "numeric", timeZone: TIME_ZONE });

/** `26 Feb 2027`, the day in Manila. */
function expiryDay(instant: Date): string {
  const parts = day.formatToParts(instant);
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  return `${get("day")} ${get("month")} ${get("year")}`;
}

/** Whether a reseller's order link still works at `now`, and the line the owner reads about it. */
export function linkStatus(reseller: Pick<ResellerView, "linkExpiresAt">, now: Date): LinkStatus {
  const expiresAt = new Date(reseller.linkExpiresAt);
  // The API refuses a link at its expiry, not only after it.
  const expired = now.getTime() >= expiresAt.getTime();
  return { expired, label: `Link ${expired ? "expired" : "expires"} ${expiryDay(expiresAt)}` };
}
