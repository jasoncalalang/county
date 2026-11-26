import { cancelOrder } from "@/lib/order-page/handlers";

export async function POST(request: Request, ctx: RouteContext<"/order/[token]/orders/[id]/cancel">) {
  const { token, id } = await ctx.params;
  return cancelOrder(token, id, await request.formData());
}
