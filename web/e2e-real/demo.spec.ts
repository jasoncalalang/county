// The demo script of functional spec section 10 against the real stack: web build, Quarkus API,
// PostgreSQL. Only the Google redirect is replaced, by an ID token the local issuer signs.
import { expect, test } from "@playwright/test";
import { signedIn } from "./support";

test.describe.configure({ mode: "serial" });

const OWNER = "demo-owner";

let link = "";

test("signed out, the dashboard sends you to sign in", async ({ page }) => {
  await page.goto("/orders");
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("button", { name: "Continue with Google" })).toBeVisible();
});

test("1-3: set up the business, add products and a reseller", async ({ browser }) => {
  const page = await signedIn(browser, OWNER);
  await page.goto("/");
  await expect(page).toHaveURL(/\/setup$/);
  await expect(page.getByRole("heading", { name: "Set up your business" })).toBeVisible();
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
  const sent = await page.evaluate(() =>
    Object.fromEntries(new FormData(document.getElementById("order") as HTMLFormElement) as unknown as [string, string][]),
  );
  await page.getByRole("button", { name: "Place order" }).click();

  await expect(page.getByRole("heading", { name: "Order #1 placed" })).toBeVisible();
  await expect(page.getByRole("cell", { name: "₱2,800.00" }).last()).toBeVisible();

  // Step 6 posts the same rendered form again, as Back and resubmit does when the browser
  // restores the page from its back/forward cache. Playwright's Chromium runs without that
  // cache, so the browser half of this step is not covered here.
  const again = await page.request.post(link, { form: sent, maxRedirects: 0 });
  expect(again.status()).toBe(303);
  expect(again.headers().location).toBe(new URL(page.url()).pathname);
  await context.close();
});

test("7-10: the wholesaler confirms, delivers, marks paid and blocks", async ({ browser }) => {
  const page = await signedIn(browser, OWNER);
  await page.goto("/orders");
  const nav = page.getByRole("navigation", { name: "Dashboard" });
  await expect(nav.getByRole("link", { name: "Orders, 1 to confirm" })).toBeVisible();
  const row = page.getByRole("row", { name: /#1/ });
  await expect(row).toHaveCount(1);
  await expect(row.getByText("Aling Rosa")).toBeVisible();
  await expect(row.getByText("10 pack Longganisa Hamonado, 5 kg Tocino")).toBeVisible();
  await expect(row.getByText("Placed")).toBeVisible();
  await expect(page.getByText("1 order · ₱2,800.00")).toBeVisible();
  await row.getByRole("button", { name: "Confirm" }).click();
  await expect(row.getByText("Confirmed")).toBeVisible();
  await expect(page.getByRole("status").getByText("Order #1 confirmed")).toBeVisible();
  await expect(nav.getByRole("link", { name: "Orders", exact: true })).toBeVisible();

  await page.getByRole("link", { name: "Production run" }).click();
  await expect(page.getByRole("row", { name: /Longganisa Hamonado pack 10 1/ })).toBeVisible();
  await expect(page.getByRole("row", { name: /Tocino kg 5 1/ })).toBeVisible();
  await expect(page.getByText("not yet confirmed")).toHaveCount(0);

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
  await expect(page.getByText(/^Confirmed \w{3} .+ by E2E Owner$/)).toBeVisible();
  await expect(page.getByText(/^Delivered \w{3} .+ by E2E Owner$/)).toBeVisible();
  await expect(page.getByText(/^Marked paid \w{3} .+ by E2E Owner$/)).toBeVisible();

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

test("settings preview the delivery date as the fields change", async ({ browser }) => {
  const page = await signedIn(browser, OWNER);
  await page.goto("/settings");
  const preview = page.getByText(/^An order placed now would be delivered on \w{3}, \d{1,2} \w{3}\.$/);
  await expect(preview).toBeVisible();
  for (const day of ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]) await page.getByLabel(day).uncheck();
  await expect(preview).toHaveCount(0);
  await page.getByLabel("Sun").check();
  await expect(page.getByText(/^An order placed now would be delivered on Sun, \d{1,2} \w{3}\.$/)).toBeVisible();
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

