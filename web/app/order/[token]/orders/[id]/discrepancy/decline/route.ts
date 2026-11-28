import { declineDiscrepancy } from "@/lib/order-page/discrepancy";

export async function POST(request: Request, ctx: RouteContext<"/order/[token]/orders/[id]/discrepancy/decline">) {
  const { token, id } = await ctx.params;
  return declineDiscrepancy(token, id, await request.formData());
}
