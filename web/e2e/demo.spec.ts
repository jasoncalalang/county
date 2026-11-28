// The demo script of functional spec section 10, against e2e/fake-api.mjs. The Google step is
// replaced by a minted Auth.js session cookie; everything after sign-in is the real web app.
import { expect, test, type Browser, type Page } from "@playwright/test";
import { encode } from "next-auth/jwt";
import { E2E_AUTH_SECRET } from "../playwright.config";

test.skip(Boolean(process.env.E2E_BASE_URL), "needs the fake API");
test.describe.configure({ mode: "serial" });

const API = "http://127.0.0.1:3199/api";
const shots = process.env.E2E_SCREENSHOTS;

async function signedIn(browser: Browser, baseURL: string, width = 1280): Promise<Page> {
  const salt = "authjs.session-token";
  const value = await encode({
    salt,
    secret: E2E_AUTH_SECRET,
    token: { sub: "e2e", name: "E2E", idToken: "e2e-id-token", expiresAt: Math.floor(Date.now() / 1000) + 3600 },
  });
  const context = await browser.newContext({ viewport: { width, height: 900 } });
  await context.addCookies([{ name: salt, value, url: baseURL, httpOnly: true, sameSite: "Lax" }]);
  return context.newPage();
}

async function shot(page: Page, name: string) {
  if (shots) await page.screenshot({ path: `${shots}/${name}.png`, fullPage: true });
}

let link = "";

test.beforeAll(async ({ request }) => {
  await request.post(`${API}/__reset`);
});

test("signed out, the dashboard sends you to sign in", async ({ page }) => {
  await page.goto("/orders");
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("button", { name: "Continue with Google" })).toBeVisible();
  await shot(page, "01-sign-in");
});

test("1-3: set up the business, add products and a reseller", async ({ browser, baseURL }) => {
  const page = await signedIn(browser, baseURL!);
  await page.goto("/");
  await expect(page).toHaveURL(/\/setup$/);
  await expect(page.getByRole("heading", { name: "Set up your business" })).toBeVisible();
  await shot(page, "02-setup");
  await page.getByLabel("Business name").fill("Nena's Longganisa");
  await page.getByRole("button", { name: "Create business" }).click();
  await expect(page).toHaveURL(/\/orders/);
  await expect(page.getByRole("heading", { name: "No orders yet" })).toBeVisible();

  await page.getByRole("link", { name: "Products" }).click();
  await expect(page.getByRole("heading", { name: "No products yet" })).toBeVisible();
  for (const [name, unit, price] of [
    ["Longganisa Hamonado", "pack", "120"],
    ["Longganisa Recado", "pack", "110"],
    ["Tocino", "kg", "320"],
  ]) {
    await page.getByRole("button", { name: "Add product" }).first().click();
    const dialog = page.getByRole("dialog", { name: "Add product" });
    await dialog.getByLabel("Name").fill(name);
    await dialog.getByLabel("Unit").selectOption(unit);
    await dialog.getByLabel("Price").fill(price);
    await dialog.getByRole("button", { name: "Add", exact: true }).click();
    await expect(page.getByRole("cell", { name })).toBeVisible();
  }
  await shot(page, "03-products");

  await page.getByRole("link", { name: "Resellers" }).click();
  await page.getByRole("button", { name: "Add reseller" }).first().click();
  const add = page.getByRole("dialog", { name: "Add reseller" });
  await add.getByLabel("Name").fill("Aling Rosa");
  await add.getByLabel("Mobile number").fill("12345");
  await add.getByRole("button", { name: "Add", exact: true }).click();
  await expect(add.getByText("Enter a Philippine mobile number")).toBeVisible();
  await add.getByLabel("Mobile number").fill("0917 123 4567");
  await add.getByRole("button", { name: "Add", exact: true }).click();
  const reveal = page.getByRole("dialog", { name: "Send this link to Aling Rosa" });
  await expect(reveal.getByText("Anyone with the link can order as Aling Rosa.")).toBeVisible();
  link = (await reveal.locator("div", { hasText: "/order/" }).last().textContent())!.trim();
  expect(link).toMatch(/\/order\/[A-Za-z0-9_-]{22}$/);
  await shot(page, "04-link-reveal");
  await reveal.getByRole("button", { name: "Done" }).click();
  await expect(page.getByRole("cell", { name: "0917 123 4567" })).toBeVisible();
  await expect(page.getByText("Invited").first()).toBeVisible();
});

test("4-6: the reseller orders from the link, and a resubmit is the same order", async ({ browser }) => {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  await page.goto(link);
  await expect(page.getByRole("heading", { name: "Nena's Longganisa" })).toBeVisible();
  await expect(page.getByText("Ordering as Aling Rosa")).toBeVisible();
  await expect(page.getByText(/^Delivery on \w{3}, \d{1,2} \w{3}$/)).toBeVisible();
  await expect(page.getByRole("spinbutton")).toHaveCount(3);
  await expect(page.getByText("Your recent orders")).toHaveCount(0);
  expect(await context.cookies()).toEqual([]);

  await page.getByLabel("Quantity of Longganisa Hamonado").fill("10");
  await page.getByLabel("Quantity of Tocino").fill("5");
  await page.getByLabel("Note for your supplier (optional)").fill("before 7am");
  await expect(page.getByText("Total ₱2,800.00")).toBeVisible();
  await shot(page, "05-order-page");
  const sent = await page.evaluate(() =>
    Object.fromEntries(new FormData(document.getElementById("order") as HTMLFormElement) as unknown as [string, string][]),
  );
  await page.getByRole("button", { name: "Place order" }).click();

  await expect(page.getByRole("heading", { name: "Order #1 placed" })).toBeVisible();
  await expect(page.getByRole("cell", { name: "₱2,800.00" }).last()).toBeVisible();
  await shot(page, "06-confirmation");

  // Step 6 posts the same rendered form again, as Back and resubmit does when the browser
  // restores the page from its back/forward cache. Playwright's Chromium runs without that
  // cache, so the browser half of this step is not covered here.
  const again = await page.request.post(link, { form: sent, maxRedirects: 0 });
  expect(again.status()).toBe(303);
  expect(again.headers().location).toBe(new URL(page.url()).pathname);
  await context.close();
});

test("7-10: the wholesaler confirms, delivers, marks paid and blocks", async ({ browser, baseURL }) => {
  const page = await signedIn(browser, baseURL!);
  await page.goto("/orders");
  const nav = page.getByRole("navigation", { name: "Dashboard" });
  await expect(nav.getByRole("link", { name: "Orders, 1 to confirm" })).toBeVisible();
  const row = page.getByRole("row", { name: /#1/ });
  await expect(row).toHaveCount(1);
  await expect(row.getByText("Aling Rosa")).toBeVisible();
  await expect(row.getByText("10 pack Longganisa Hamonado, 5 kg Tocino")).toBeVisible();
  await expect(row.getByText("Placed")).toBeVisible();
  await expect(page.getByText("1 order · ₱2,800.00")).toBeVisible();
  await shot(page, "07-orders");
  await row.getByRole("button", { name: "Confirm" }).click();
  await expect(row.getByText("Confirmed")).toBeVisible();
  await expect(page.getByRole("status").getByText("Order #1 confirmed")).toBeVisible();
  await expect(nav.getByRole("link", { name: "Orders", exact: true })).toBeVisible();

  await page.getByRole("link", { name: "Production run" }).click();
  await expect(page.getByRole("row", { name: /Longganisa Hamonado pack 10 1/ })).toBeVisible();
  await expect(page.getByRole("row", { name: /Tocino kg 5 1/ })).toBeVisible();
  await expect(page.getByText("not yet confirmed")).toHaveCount(0);
  await shot(page, "08-production-run");

  await page.getByRole("link", { name: "Orders", exact: true }).click();
  await row.getByRole("button", { name: "Deliver" }).click();
  await page.getByRole("dialog", { name: "Was order #1 paid?" }).getByRole("button", { name: "No" }).click();
  await expect(row.getByText("Unpaid")).toBeVisible();
  await row.getByRole("button", { name: "Mark paid" }).click();
  await expect(row.getByText("Paid", { exact: true })).toBeVisible();

  await page.getByRole("link", { name: "#1" }).first().click();
  await expect(page.getByRole("heading", { name: "Order #1" })).toBeVisible();
  await expect(page.getByText("before 7am")).toBeVisible();
  await expect(page.getByText(/^Placed \w{3} .+ by Aling Rosa$/)).toBeVisible();
  await expect(page.getByText(/^Confirmed \w{3} .+ by E2E$/)).toBeVisible();
  await expect(page.getByText(/^Delivered \w{3} .+ by E2E$/)).toBeVisible();
  await expect(page.getByText(/^Marked paid \w{3} .+ by E2E$/)).toBeVisible();
  await shot(page, "09-order-detail");

  await page.getByRole("link", { name: "Resellers" }).click();
  const rosa = page.getByRole("row", { name: /Aling Rosa/ });
  await expect(rosa.getByText("Active")).toBeVisible();
  await rosa.getByRole("button", { name: "Block" }).click();
  await page.getByRole("dialog", { name: "Block Aling Rosa?" }).getByRole("button", { name: "Block" }).click();
  await expect(rosa.getByText("Blocked")).toBeVisible();

  const reseller = await browser.newPage();
  const blocked = await reseller.goto(link);
  expect(blocked?.status()).toBe(404);
  await expect(reseller.getByText("This link is no longer active.")).toBeVisible();
  await expect(reseller.getByText("Nena")).toHaveCount(0);

  await rosa.getByRole("button", { name: "Unblock" }).click();
  await expect(rosa.getByText("Active")).toBeVisible();
  await reseller.goto(link);
  await expect(reseller.getByText("Ordering as Aling Rosa")).toBeVisible();
});

test("settings preview the delivery date as the fields change", async ({ browser, baseURL }) => {
  const page = await signedIn(browser, baseURL!);
  await page.goto("/settings");
  const preview = page.getByText(/^An order placed now would be delivered on \w{3}, \d{1,2} \w{3}\.$/);
  await expect(preview).toBeVisible();
  for (const day of ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]) await page.getByLabel(day).uncheck();
  await expect(preview).toHaveCount(0);
  await page.getByLabel("Sun").check();
  await expect(page.getByText(/^An order placed now would be delivered on Sun, \d{1,2} \w{3}\.$/)).toBeVisible();
  await shot(page, "10-settings");
});

test("the order page works with JavaScript off, including cancel", async ({ browser }) => {
  const context = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  await page.goto(link);

  await page.getByRole("button", { name: "Place order" }).click();
  await expect(page.getByRole("alert")).toHaveText("Add at least one product.");

  await page.getByLabel("Quantity of Longganisa Recado").fill("20");
  await page.getByRole("button", { name: "Place order" }).click();
  await expect(page.getByRole("heading", { name: "Order #2 placed" })).toBeVisible();
  await expect(page.getByText("You can cancel it until then.")).toBeVisible();

  await page.getByRole("button", { name: "Cancel order" }).click();
  await expect(page.getByRole("heading", { name: "Cancel order #2?" })).toBeVisible();
  await page.getByRole("button", { name: "Cancel order" }).click();
  await expect(page.getByRole("heading", { name: "Order #2 cancelled" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Cancel order" })).toHaveCount(0);

  await page.getByRole("link", { name: "Back to price list" }).click();
  await expect(page.getByText("Your recent orders")).toBeVisible();
  await context.close();
});

test("the order page stays under 50 KB", async ({ request }) => {
  const response = await request.get(link);
  expect((await response.body()).length).toBeLessThan(50 * 1024);
});

let quoteUrl = "";

test("quotes: the wholesaler drafts, checks and sends a quote", async ({ browser, baseURL }) => {
  const page = await signedIn(browser, baseURL!);
  await page.goto("/orders");
  await page.getByRole("navigation", { name: "Dashboard" }).getByRole("link", { name: "Quotes" }).click();
  await expect(page.getByRole("heading", { name: "No quotes yet" })).toBeVisible();
  await page.getByRole("link", { name: "New quote" }).first().click();

  await page.getByRole("button", { name: "Save draft" }).click();
  await expect(page.getByText("Choose a reseller")).toHaveCount(2);
  await expect(page.getByText("Add at least one product")).toBeVisible();

  await page.getByLabel("Reseller", { exact: true }).selectOption({ label: "Aling Rosa" });
  await page.getByLabel("Quantity of Longganisa Hamonado").fill("10");
  await page.getByLabel("Quantity of Tocino").fill("2");
  await page.getByLabel("Note for the reseller (optional)").fill("Holiday price");
  await shot(page, "11-new-quote");
  await page.getByRole("button", { name: "Save draft" }).click();

  await expect(page).toHaveURL(/\/quotes\/\d+$/);
  await expect(page.getByRole("heading", { name: "Quote #Q1" })).toBeVisible();
  await expect(page.getByText("Draft", { exact: true })).toBeVisible();
  await expect(page.getByRole("cell", { name: "₱1,840.00" })).toBeVisible();
  await expect(page.getByRole("blockquote")).toHaveText("Holiday price");
  await page.getByRole("button", { name: "Send" }).click();

  const share = page.getByRole("dialog", { name: "Send quote #Q1 to Aling Rosa" });
  quoteUrl = (await share.locator("div", { hasText: "/quotes/" }).last().textContent())!.trim();
  expect(quoteUrl).toBe(`${link}/quotes/${new URL(page.url()).pathname.split("/").pop()}`);
  await shot(page, "12-quote-sent");
  await share.getByRole("button", { name: "Done" }).click();
  await expect(page.getByText("Sent", { exact: true })).toBeVisible();
});

test("quotes: the reseller accepts from the link, with JavaScript off", async ({ browser }) => {
  const context = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  await page.goto(link);
  await expect(page.getByText("Quotes for you")).toBeVisible();
  await page.getByRole("link", { name: /^#Q1 · ₱1,840\.00 · accept by / }).click();
  await expect(page).toHaveURL(quoteUrl);
  await expect(page.getByRole("heading", { name: "Quote #Q1" })).toBeVisible();
  await expect(page.getByText("Holiday price")).toBeVisible();
  await shot(page, "13-reseller-quote");

  await page.getByRole("button", { name: "Accept and place order" }).click();
  await expect(page.getByRole("heading", { name: "Order #3 placed" })).toBeVisible();
  await expect(page.getByRole("cell", { name: "₱1,840.00" }).last()).toBeVisible();

  await page.goto(quoteUrl);
  await expect(page.getByText("You accepted this quote.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Accept and place order" })).toHaveCount(0);
  await page.goto(link);
  await expect(page.getByText("Quotes for you")).toHaveCount(0);
  await context.close();
});

test("quotes: the wholesaler sees the acceptance and its order; a declined quote", async ({ browser, baseURL }) => {
  const page = await signedIn(browser, baseURL!);
  await page.goto("/quotes");
  const row = page.getByRole("row", { name: /#Q1/ });
  await expect(row.getByText("Accepted")).toBeVisible();
  await row.getByRole("link", { name: "#Q1" }).click();
  await page.getByRole("link", { name: "View order" }).click();
  await expect(page.getByRole("heading", { name: "Order #3" })).toBeVisible();

  await page.goto("/quotes/new");
  await page.getByLabel("Reseller", { exact: true }).selectOption({ label: "Aling Rosa" });
  await page.getByLabel("Quantity of Tocino").fill("1");
  await page.getByRole("button", { name: "Save draft" }).click();
  await expect(page.getByRole("heading", { name: "Quote #Q2" })).toBeVisible();
  await page.getByRole("button", { name: "Send" }).click();
  const second = (await page.getByRole("dialog").locator("div", { hasText: "/quotes/" }).last().textContent())!.trim();

  const reseller = await browser.newPage();
  reseller.on("dialog", (dialog) => dialog.accept());
  await reseller.goto(second);
  await reseller.getByRole("button", { name: "Decline" }).click();
  await expect(reseller.getByText("You declined this quote.")).toBeVisible();

  await page.goto("/quotes?status=declined");
  await expect(page.getByRole("row", { name: /#Q2/ }).getByText("Declined")).toBeVisible();
  await expect(page.getByRole("row", { name: /#Q1/ })).toHaveCount(0);
  await shot(page, "14-quotes");
});

test("fractional quantities: kg takes 1.5, a pack does not, and the total rounds half up", async ({ browser, request }) => {
  // 99.99 x 0.333 = 33.29667, which is 33.30 half up.
  const added = await request.post(`${API}/products`, {
    headers: { authorization: "Bearer e2e" },
    data: { name: "Ube Halaya", unit: "kg", price: 99.99 },
  });
  expect(added.status()).toBe(201);

  const live = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const withScript = await live.newPage();
  await withScript.goto(link);
  await withScript.getByLabel("Quantity of Ube Halaya").fill("0.333");
  await expect(withScript.getByText("Total ₱33.30")).toBeVisible();
  await withScript.getByLabel("Quantity of Tocino").fill("1.5");
  await expect(withScript.getByText("Total ₱513.30")).toBeVisible();
  await live.close();

  const context = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  await page.goto(link);
  // The browser stops a half pack before it is sent, so the server's answer is read by posting
  // the rendered form directly, as a client that skips the browser's check would.
  const pack = (await page.getByLabel("Quantity of Longganisa Hamonado").getAttribute("name"))!;
  const key = await page.locator('input[name="idempotencyKey"]').inputValue();
  const refused = await page.request.post(link, { form: { idempotencyKey: key, [pack]: "1.5" } });
  expect(refused.status()).toBe(422);
  expect(await refused.text()).toContain("Enter a whole number from 0 to 9999.");

  await page.getByLabel("Quantity of Tocino").fill("1.5");
  await page.getByLabel("Quantity of Ube Halaya").fill("0.333");
  await page.getByRole("button", { name: "Place order" }).click();
  await expect(page.getByRole("heading", { name: /^Order #\d+ placed$/ })).toBeVisible();
  await expect(page.getByRole("cell", { name: "1.5 kg × ₱320.00" })).toBeVisible();
  await expect(page.getByRole("cell", { name: "₱480.00" })).toBeVisible();
  await expect(page.getByRole("cell", { name: "0.333 kg × ₱99.99" })).toBeVisible();
  await expect(page.getByRole("cell", { name: "₱33.30" })).toBeVisible();
  await expect(page.getByRole("cell", { name: "₱513.30" })).toBeVisible();
  await context.close();
});

test("short delivery: the reseller reports 8 of 10 received, with JavaScript off", async ({ browser }) => {
  // Order #1 was delivered above: 10 pack of Longganisa Hamonado at 120.00.
  const context = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  await page.goto(`${link}/orders/1`);
  await page.getByRole("link", { name: "Report a problem with this delivery" }).click();
  await expect(page).toHaveURL(`${link}/orders/1/discrepancy`);
  await expect(page.getByRole("heading", { name: "Report a problem with order #1" })).toBeVisible();
  await expect(page.getByLabel("Received quantity of Longganisa Hamonado")).toHaveValue("10");
  await expect(page.getByLabel("Received quantity of Tocino")).toHaveValue("5");
  await shot(page, "15-report-form");

  await page.getByRole("button", { name: "Send report" }).click();
  await expect(page.getByRole("alert")).toContainText("Change at least one received quantity to report a problem.");

  await page.getByLabel("Received quantity of Longganisa Hamonado").fill("8");
  await page.getByLabel("Note for your supplier (optional)").fill("2 packs were crushed");
  await page.getByRole("button", { name: "Send report" }).click();

  await expect(page).toHaveURL(`${link}/orders/1/discrepancy`);
  await expect(page.getByRole("heading", { name: "Delivery report for order #1" })).toBeVisible();
  await expect(page.getByText("Open", { exact: true })).toBeVisible();
  await expect(page.getByRole("cell", { name: "8 of 10 pack" })).toBeVisible();
  await expect(page.getByRole("cell", { name: "₱240.00 short" })).toBeVisible();
  await expect(page.getByText("2 packs were crushed")).toBeVisible();
  await expect(page.getByText("Waiting for your supplier")).toBeVisible();
  await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
  await shot(page, "16-report-sent");

  await page.goto(`${link}/orders/1`);
  await expect(page.getByRole("link", { name: "View report" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Report a problem with this delivery" })).toHaveCount(0);
  await context.close();
});
