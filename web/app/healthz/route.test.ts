import { expect, test } from "vitest";
import { GET } from "./route";

test("healthz answers 200 ok", async () => {
  const response = GET();

  expect(response.status).toBe(200);
  expect(await response.text()).toBe("ok");
});
