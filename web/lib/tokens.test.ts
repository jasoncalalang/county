import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test } from "vitest";
import { ORDER_PAGE_CSS } from "./order-page/styles";

// The order page cannot import the dashboard's stylesheet (UI design, section 11), so it repeats
// the colour tokens. This keeps the copies equal.
test("order page colours match app/tokens.css", () => {
  const tokens = readFileSync(join(__dirname, "..", "app", "tokens.css"), "utf8");
  const declared = (css: string) =>
    Object.fromEntries([...css.matchAll(/--([a-z-]+):\s*(#[0-9a-f]{6})/gi)].map((m) => [m[1], m[2].toLowerCase()]));
  const shared = declared(tokens);
  const page = declared(ORDER_PAGE_CSS);

  for (const [name, value] of Object.entries(page)) expect(shared[name], name).toBe(value);
});
