import { declineQuote } from "@/lib/order-page/handlers";

export async function POST(request: Request, ctx: RouteContext<"/order/[token]/quotes/[id]/decline">) {
  const { token, id } = await ctx.params;
  return declineQuote(token, id, await request.formData());
}
