// Tenant scope comes from the verified identity. Two owners, each with a business, must not see
// or reach each other's data, through the UI or through a guessed id.
import { expect, test } from "@playwright/test";
import { addProduct, addReseller, createBusiness, ownerApi, signedIn } from "./support";

const ALICE = "isolation-alice";
const BOB = "isolation-bob";

test("one owner's data is invisible to another owner", async ({ browser }) => {
  const alice = await signedIn(browser, ALICE);
  await createBusiness(alice, "Alice Foods");
  await alice.getByRole("link", { name: "Products" }).click();
  await addProduct(alice, "Alice Tocino", "kg", "300");
  await alice.getByRole("link", { name: "Resellers" }).click();
  const aliceLink = await addReseller(alice, "Alice Reseller", "0917 000 1111");

  const reseller = await browser.newPage();
  await reseller.goto(aliceLink);
  await reseller.getByLabel("Quantity of Alice Tocino").fill("2");
  await reseller.getByRole("button", { name: "Place order" }).click();
  await expect(reseller.getByRole("heading", { name: "Order #1 placed" })).toBeVisible();

  const bob = await signedIn(browser, BOB);
  await createBusiness(bob, "Bob Foods");
  await expect(bob.getByRole("heading", { name: "No orders yet" })).toBeVisible();
  await bob.getByRole("link", { name: "Products" }).click();
  await expect(bob.getByRole("heading", { name: "No products yet" })).toBeVisible();
  await expect(bob.getByText("Alice Tocino")).toHaveCount(0);
  await bob.getByRole("link", { name: "Resellers" }).click();
  await expect(bob.getByText("Alice Reseller")).toHaveCount(0);

  // A guessed id answers like a missing one, never with Alice's order.
  const aliceOrders = await ownerApi(ALICE, "/orders");
  expect(aliceOrders.body).toHaveLength(1);
  const aliceOrderId = aliceOrders.body[0].id;
  expect((await ownerApi(ALICE, `/orders/${aliceOrderId}`)).status).toBe(200);
  expect((await ownerApi(BOB, `/orders/${aliceOrderId}`)).status).toBe(404);
  expect((await ownerApi(BOB, `/orders/${aliceOrderId}/confirm`, { method: "POST" })).status).toBe(404);
  expect((await ownerApi(ALICE, `/orders/${aliceOrderId}`)).body.status).toBe("placed");
});
