import { defineConfig } from "@playwright/test";

// Set E2E_BASE_URL to run against a deployed environment (the pre-demo check).
// Without it, the tests start the production build of this checkout.
const port = 3100;
const baseURL = process.env.E2E_BASE_URL ?? `http://localhost:${port}`;

export default defineConfig({
  testDir: "e2e",
  use: { baseURL },
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: "npm run build && npm run start",
        env: { PORT: String(port), HOSTNAME: "127.0.0.1" },
        url: `${baseURL}/healthz`,
        timeout: 180_000,
        reuseExistingServer: !process.env.CI,
      },
});
