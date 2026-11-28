// Request handling for the reseller's short-delivery report: the form, the report, and the
// approve and decline of a report the supplier filed. Kept apart from handlers.ts.
import type { Fetch } from "./api";

export async function showDiscrepancy(token: string, rawId: string, fetchImpl?: Fetch): Promise<Response> {
  throw new Error("not implemented");
}

export async function fileDiscrepancy(token: string, rawId: string, form: FormData, fetchImpl?: Fetch): Promise<Response> {
  throw new Error("not implemented");
}

export async function approveDiscrepancy(token: string, rawId: string, form: FormData, fetchImpl?: Fetch): Promise<Response> {
  throw new Error("not implemented");
}

export async function declineDiscrepancy(token: string, rawId: string, form: FormData, fetchImpl?: Fetch): Promise<Response> {
  throw new Error("not implemented");
}
