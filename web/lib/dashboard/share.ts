import type { ResellerView } from "../api-types";

/** The ready-to-paste message of functional spec 3.3. */
export function shareMessage(reseller: Pick<ResellerView, "name" | "orderLink">, business: string): string {
  return `Hi ${reseller.name}, you can order from ${business} here: ${reseller.orderLink}`;
}
