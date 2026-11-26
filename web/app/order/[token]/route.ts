import { placeOrder, showPriceList } from "@/lib/order-page/handlers";

// The reseller's price list: plain HTML from the server, no React runtime (ADR 0003, UI 7.1).
export async function GET(_request: Request, ctx: RouteContext<"/order/[token]">) {
  const { token } = await ctx.params;
  return showPriceList(token);
}

export async function POST(request: Request, ctx: RouteContext<"/order/[token]">) {
  const { token } = await ctx.params;
  return placeOrder(token, await request.formData());
}
