import { approveDiscrepancy } from "@/lib/order-page/discrepancy";

export async function POST(request: Request, ctx: RouteContext<"/order/[token]/orders/[id]/discrepancy/approve">) {
  const { token, id } = await ctx.params;
  return approveDiscrepancy(token, id, await request.formData());
}
