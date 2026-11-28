import { showQuote } from "@/lib/order-page/handlers";

// A quote on the reseller's link: plain HTML, like the price list (ADR 0003).
export async function GET(_request: Request, ctx: RouteContext<"/order/[token]/quotes/[id]">) {
  const { token, id } = await ctx.params;
  return showQuote(token, id);
}
