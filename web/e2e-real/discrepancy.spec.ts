// Short-delivery reports through the real stack: the reseller reports with JavaScript off and the
// owner approves; then the owner reports and the reseller declines on the link.
import { expect, test, type Browser, type Page } from "@playwright/test";
import { addProduct, addReseller, createBusiness, ownerApi, signedIn } from "./support";

test.describe.configure({ mode: "serial" });

const OWNER = "discrepancy-owner";
let link = "";
let firstOrder = "";
let secondOrder = "";

/** A phone with JavaScript off: how the order page must work for any reseller. */
async function resellerPage(browser: Browser): Promise<Page> {
  const context = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 390, height: 844 } });
  return context.newPage();
}

/** Places an order of Longganisa Hamonado from the link; returns the order's page URL. */
async function place(browser: Browser, quantity: string, heading: string): Promise<string> {
  const page = await resellerPage(browser);
  await page.goto(link);
  await page.getByLabel("Quantity of Longganisa Hamonado").fill(quantity);
  await page.getByRole("button", { name: "Place order" }).click();
  await expect(page.getByRole("heading", { name: heading })).toBeVisible();
  const url = page.url();
  await page.context().close();
  return url;
}

test("set up a business with a product and a reseller", async ({ browser }) => {
  const page = await signedIn(browser, OWNER);
  await createBusiness(page, "Report Test Foods");
  await page.getByRole("link", { name: "Products" }).click();
  await addProduct(page, "Longganisa Hamonado", "pack", "120");
  await page.getByRole("link", { name: "Resellers" }).click();
  link = await addReseller(page, "Aling Rosa", "0917 123 4567");
});

test("the reseller orders 10 and the owner confirms and delivers", async ({ browser }) => {
  firstOrder = await place(browser, "10", "Order #1 placed");

  const page = await signedIn(browser, OWNER);
  await page.goto("/orders");
  const row = page.getByRole("row", { name: /#1/ });
  await row.getByRole("button", { name: "Confirm" }).click();
  await expect(row.getByText("Confirmed")).toBeVisible();
  await row.getByRole("button", { name: "Deliver" }).click();
  await page.getByRole("dialog", { name: "Was order #1 paid?" }).getByRole("button", { name: "No" }).click();
  await expect(row.getByText("Unpaid")).toBeVisible();
});

test("the reseller reports 8 of 10 received, with JavaScript off", async ({ browser }) => {
  const page = await resellerPage(browser);
  await page.goto(firstOrder);
  await page.getByRole("link", { name: "Report a problem with this delivery" }).click();
  await expect(page).toHaveURL(`${firstOrder}/discrepancy`);
  await expect(page.getByLabel("Received quantity of Longganisa Hamonado")).toHaveValue("10");

  await page.getByRole("button", { name: "Send report" }).click();
  await expect(page.getByRole("alert")).toContainText("Change at least one received quantity to report a problem.");

  await page.getByLabel("Received quantity of Longganisa Hamonado").fill("8");
  await page.getByLabel("Note for your supplier (optional)").fill("2 packs were crushed");
  await page.getByRole("button", { name: "Send report" }).click();

  await expect(page.getByRole("heading", { name: "Delivery report for order #1" })).toBeVisible();
  await expect(page.getByText("Open", { exact: true })).toBeVisible();
  await expect(page.getByRole("cell", { name: "8 of 10 pack" })).toBeVisible();
  await expect(page.getByRole("cell", { name: "₱240.00 short" })).toBeVisible();
  await expect(page.getByText("2 packs were crushed")).toBeVisible();
  await expect(page.getByText("Waiting for your supplier")).toBeVisible();
  await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
  await page.context().close();
});

test("the owner finds it under Delivery reports, opens the order and approves with a note", async ({ browser }) => {
  const page = await signedIn(browser, OWNER);
  await page.goto("/orders");
  await page.getByRole("navigation", { name: "Dashboard" }).getByRole("link", { name: "Delivery reports" }).click();
  const row = page.getByRole("row", { name: /#1/ });
  await expect(row.getByText("Aling Rosa")).toBeVisible();
  await expect(row.getByText("₱240.00 short")).toBeVisible();

  await row.getByRole("link", { name: "#1" }).click();
  const report = page.getByRole("region", { name: "Delivery report" });
  await expect(report.getByText("Open", { exact: true })).toBeVisible();
  await expect(report.getByRole("cell", { name: "8 of 10 pack" })).toBeVisible();
  await expect(report.getByText("2 packs were crushed")).toBeVisible();
  await expect(report.getByText(/^The reseller reported this /)).toBeVisible();

  await report.getByRole("button", { name: "Approve" }).click();
  const dialog = page.getByRole("dialog", { name: "Approve this report?" });
  await dialog.getByLabel("Note for the reseller (optional)").fill("Sorry about that");
  await dialog.getByRole("button", { name: "Approve report" }).click();
  await expect(report.getByText("Approved", { exact: true })).toBeVisible();
  await expect(report.getByText("Sorry about that")).toBeVisible();
  await expect(report.getByText(/^You approved this /)).toBeVisible();

  await page.goto("/discrepancies");
  await expect(page.getByRole("heading", { name: "No open reports" })).toBeVisible();
});

test("the reseller's report page shows it approved", async ({ browser }) => {
  const page = await resellerPage(browser);
  await page.goto(firstOrder);
  await expect(page.getByText("Approved", { exact: true })).toBeVisible();
  await page.getByRole("link", { name: "View report" }).click();
  await expect(page.getByText("Approved", { exact: true })).toBeVisible();
  await expect(page.getByText("Your supplier approved this")).toBeVisible();
  await expect(page.getByText("Sorry about that")).toBeVisible();
  await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
  await page.context().close();
});

test("the owner files a report on a second order and the reseller declines it", async ({ browser }) => {
  secondOrder = await place(browser, "5", "Order #2 placed");
  const id = secondOrder.split("/").pop()!;
  expect((await ownerApi(OWNER, `/orders/${id}/confirm`, { method: "POST" })).status).toBe(200);
  expect((await ownerApi(OWNER, `/orders/${id}/deliver`, { method: "POST", body: { paid: false } })).status).toBe(200);

  const page = await signedIn(browser, OWNER);
  await page.goto(`/orders/${id}`);
  await page.getByRole("button", { name: "Report a short delivery" }).click();
  const dialog = page.getByRole("dialog", { name: "Report a short delivery" });
  await expect(dialog.getByLabel("Received quantity of Longganisa Hamonado")).toHaveValue("5");
  await dialog.getByLabel("Received quantity of Longganisa Hamonado").fill("4");
  await dialog.getByLabel("Note for the reseller (optional)").fill("One pack was missing");
  await dialog.getByRole("button", { name: "File report" }).click();

  const report = page.getByRole("region", { name: "Delivery report" });
  await expect(report.getByText("Open", { exact: true })).toBeVisible();
  await expect(report.getByRole("cell", { name: "4 of 5 pack" })).toBeVisible();
  await expect(report.getByRole("cell", { name: "₱120.00 short" })).toBeVisible();
  await expect(report.getByText(/^You reported this /)).toBeVisible();
  await expect(report.getByText("Waiting for the reseller to answer.")).toBeVisible();
  await expect(report.getByRole("button", { name: "Approve" })).toHaveCount(0);

  const reseller = await resellerPage(browser);
  await reseller.goto(secondOrder);
  await reseller.getByRole("link", { name: "View report" }).click();
  await expect(reseller.getByRole("heading", { name: "Delivery report for order #2" })).toBeVisible();
  await expect(reseller.getByText("Your supplier reported this")).toBeVisible();
  await expect(reseller.getByText("One pack was missing")).toBeVisible();
  await reseller.getByRole("button", { name: "Decline" }).click();
  await expect(reseller.getByRole("heading", { name: "Decline this report?" })).toBeVisible();
  await reseller.getByLabel("Note for your supplier (optional)").fill("We counted five");
  await reseller.getByRole("button", { name: "Decline report" }).click();
  await expect(reseller.getByText("Declined", { exact: true })).toBeVisible();
  await expect(reseller.getByText("We counted five")).toBeVisible();
  await expect(reseller.getByText("You declined this")).toBeVisible();
  await reseller.context().close();

  await page.reload();
  await expect(report.getByText("Declined", { exact: true })).toBeVisible();
  await expect(report.getByText("We counted five")).toBeVisible();
  await expect(report.getByText(/^The reseller declined this /)).toBeVisible();
});
