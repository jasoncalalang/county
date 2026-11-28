import { acceptQuote } from "@/lib/order-page/handlers";

export async function POST(request: Request, ctx: RouteContext<"/order/[token]/quotes/[id]/accept">) {
  const { token, id } = await ctx.params;
  return acceptQuote(token, id, await request.formData());
}
