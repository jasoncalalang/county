import { expect, type Browser, type Page } from "@playwright/test";
import { encode } from "next-auth/jwt";
import { API_URL, AUTH_SECRET, OIDC_URL, WEB_URL } from "./env";

/** An ID token signed by the local issuer, which the real API verifies like one from Google. */
export async function mintIdToken(sub: string, name = "E2E Owner"): Promise<string> {
  const response = await fetch(`${OIDC_URL}/mint`, {
    method: "POST",
    body: JSON.stringify({ sub, name, email: `${sub}@example.test` }),
  });
  return ((await response.json()) as { idToken: string }).idToken;
}

/**
 * A browser context signed in as the owner with this subject. Only the Google redirect is
 * replaced: the Auth.js cookie carries a token the local issuer signed, so the API's
 * verification and subject-to-tenant resolution run for real.
 */
export async function signedIn(browser: Browser, sub: string, width = 1280): Promise<Page> {
  const salt = "authjs.session-token";
  const value = await encode({
    salt,
    secret: AUTH_SECRET,
    token: { sub, name: "E2E Owner", idToken: await mintIdToken(sub), expiresAt: Math.floor(Date.now() / 1000) + 3000 },
  });
  const context = await browser.newContext({ viewport: { width, height: 900 } });
  await context.addCookies([{ name: salt, value, url: WEB_URL, httpOnly: true, sameSite: "Lax" }]);
  return context.newPage();
}

/** Calls the owner API directly, for set-up a test is not about. */
export async function ownerApi(sub: string, path: string, init: { method?: string; body?: unknown } = {}) {
  const response = await fetch(`${API_URL}${path}`, {
    method: init.method ?? "GET",
    headers: { authorization: `Bearer ${await mintIdToken(sub)}`, "content-type": "application/json" },
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  });
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : undefined };
}

/** Walks the setup screen for a brand-new owner and lands on Orders. */
export async function createBusiness(page: Page, name: string) {
  await page.goto("/");
  await expect(page).toHaveURL(/\/setup$/);
  await page.getByLabel("Business name").fill(name);
  await page.getByRole("button", { name: "Create business" }).click();
  await expect(page).toHaveURL(/\/orders/);
}

export async function addProduct(page: Page, name: string, unit: string, price: string) {
  await page.getByRole("button", { name: "Add product" }).first().click();
  const dialog = page.getByRole("dialog", { name: "Add product" });
  await dialog.getByLabel("Name").fill(name);
  await dialog.getByLabel("Unit").selectOption(unit);
  await dialog.getByLabel("Price").fill(price);
  await dialog.getByRole("button", { name: "Add", exact: true }).click();
  await expect(page.getByRole("cell", { name })).toBeVisible();
}

/** Adds a reseller through the dialog and returns the order link it reveals. */
export async function addReseller(page: Page, name: string, mobile: string): Promise<string> {
  await page.getByRole("button", { name: "Add reseller" }).first().click();
  const add = page.getByRole("dialog", { name: "Add reseller" });
  await add.getByLabel("Name").fill(name);
  await add.getByLabel("Mobile number").fill(mobile);
  await add.getByRole("button", { name: "Add", exact: true }).click();
  const reveal = page.getByRole("dialog", { name: `Send this link to ${name}` });
  const link = (await reveal.locator("div", { hasText: "/order/" }).last().textContent())!.trim();
  await reveal.getByRole("button", { name: "Done" }).click();
  return link;
}
