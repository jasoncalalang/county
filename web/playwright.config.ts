import { defineConfig } from "@playwright/test";

// Set E2E_BASE_URL to run against a deployed environment (the pre-demo check).
// Without it, the tests start the production build of this checkout against e2e/fake-api.mjs,
// an in-memory stand-in for the API, and sign in with a minted Auth.js session cookie.
const port = 3100;
const apiPort = 3199;
const baseURL = process.env.E2E_BASE_URL ?? `http://localhost:${port}`;
export const E2E_AUTH_SECRET = "e2e-only-secret-not-for-any-real-environment";

export default defineConfig({
  testDir: "e2e",
  use: { baseURL },
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : [
        {
          command: "node e2e/fake-api.mjs",
          env: { FAKE_API_PORT: String(apiPort), PUBLIC_URL: baseURL },
          port: apiPort,
          reuseExistingServer: !process.env.CI,
        },
        {
          // E2E_PREBUILT=1 skips the build when an earlier step already ran `npm run build`.
          command: process.env.E2E_PREBUILT ? "npm run start" : "npm run build && npm run start",
          env: {
            PORT: String(port),
            HOSTNAME: "127.0.0.1",
            AUTH_SECRET: E2E_AUTH_SECRET,
            AUTH_GOOGLE_ID: "e2e",
            AUTH_GOOGLE_SECRET: "e2e",
            COUNTY_API_URL: `http://127.0.0.1:${apiPort}/api`,
            COUNTY_DEV_API_ORIGIN: `http://127.0.0.1:${apiPort}`,
          },
          url: `${baseURL}/healthz`,
          timeout: 180_000,
          reuseExistingServer: !process.env.CI,
        },
      ],
});
