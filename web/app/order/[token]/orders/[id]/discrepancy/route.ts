import { fileDiscrepancy, showDiscrepancy } from "@/lib/order-page/discrepancy";

// The reseller's short-delivery report: plain HTML, like the rest of the order link (ADR 0003).
export async function GET(_request: Request, ctx: RouteContext<"/order/[token]/orders/[id]/discrepancy">) {
  const { token, id } = await ctx.params;
  return showDiscrepancy(token, id);
}

export async function POST(request: Request, ctx: RouteContext<"/order/[token]/orders/[id]/discrepancy">) {
  const { token, id } = await ctx.params;
  return fileDiscrepancy(token, id, await request.formData());
}
