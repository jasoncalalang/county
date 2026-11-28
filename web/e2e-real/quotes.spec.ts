// Quotes through the real stack: draft, send, accept (no JavaScript), decline, and the owner
// seeing the order an accepted quote placed.
import { expect, test } from "@playwright/test";
import { addProduct, addReseller, createBusiness, signedIn } from "./support";

test.describe.configure({ mode: "serial" });

const OWNER = "quotes-owner";
let link = "";
let quoteUrl = "";

test("set up a business with a reseller", async ({ browser }) => {
  const page = await signedIn(browser, OWNER);
  await createBusiness(page, "Quote Test Foods");
  await page.getByRole("link", { name: "Products" }).click();
  await addProduct(page, "Longganisa Hamonado", "pack", "120");
  await addProduct(page, "Tocino", "kg", "320");
  await page.getByRole("link", { name: "Resellers" }).click();
  link = await addReseller(page, "Aling Rosa", "0917 123 4567");
});

test("the wholesaler drafts, checks and sends a quote", async ({ browser }) => {
  const page = await signedIn(browser, OWNER);
  await page.goto("/quotes");
  await expect(page.getByRole("heading", { name: "No quotes yet" })).toBeVisible();
  await page.getByRole("link", { name: "New quote" }).first().click();

  await page.getByRole("button", { name: "Save draft" }).click();
  await expect(page.getByText("Choose a reseller")).toHaveCount(2);
  await expect(page.getByText("Add at least one product")).toBeVisible();

  await page.getByLabel("Reseller", { exact: true }).selectOption({ label: "Aling Rosa" });
  await page.getByLabel("Quantity of Longganisa Hamonado").fill("10");
  await page.getByLabel("Quantity of Tocino").fill("2");
  await page.getByLabel("Note for the reseller (optional)").fill("Holiday price");
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
  await share.getByRole("button", { name: "Done" }).click();
  await expect(page.getByText("Sent", { exact: true })).toBeVisible();
});

test("the reseller accepts from the link, with JavaScript off", async ({ browser }) => {
  const context = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  await page.goto(link);
  await expect(page.getByText("Quotes for you")).toBeVisible();
  await page.getByRole("link", { name: /^#Q1 · ₱1,840\.00 · accept by / }).click();
  await expect(page).toHaveURL(quoteUrl);
  await expect(page.getByText("Holiday price")).toBeVisible();

  await page.getByRole("button", { name: "Accept and place order" }).click();
  await expect(page.getByRole("heading", { name: "Order #1 placed" })).toBeVisible();
  await expect(page.getByRole("cell", { name: "₱1,840.00" }).last()).toBeVisible();

  await page.goto(quoteUrl);
  await expect(page.getByText("You accepted this quote.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Accept and place order" })).toHaveCount(0);
  await page.goto(link);
  await expect(page.getByText("Quotes for you")).toHaveCount(0);
  await context.close();
});

test("the wholesaler sees the acceptance and its order, then a declined quote", async ({ browser }) => {
  const page = await signedIn(browser, OWNER);
  await page.goto("/quotes");
  const row = page.getByRole("row", { name: /#Q1/ });
  await expect(row.getByText("Accepted")).toBeVisible();
  await row.getByRole("link", { name: "#Q1" }).click();
  await page.getByRole("link", { name: "View order" }).click();
  await expect(page.getByRole("heading", { name: "Order #1" })).toBeVisible();

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
});
