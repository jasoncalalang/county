import { showOrder } from "@/lib/order-page/handlers";

export async function GET(_request: Request, ctx: RouteContext<"/order/[token]/orders/[id]">) {
  const { token, id } = await ctx.params;
  return showOrder(token, id);
}
