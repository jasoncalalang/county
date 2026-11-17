import { expect, test } from "@playwright/test";

test("home page renders", async ({ page }) => {
  await page.goto("/");

  await expect(page.getByRole("heading", { level: 1, name: "county" })).toBeVisible();
});

test("healthz answers ok", async ({ request }) => {
  const response = await request.get("/healthz");

  expect(response.status()).toBe(200);
  expect(await response.text()).toBe("ok");
});
