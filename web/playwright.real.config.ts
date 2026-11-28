import { defineConfig } from "@playwright/test";
import { API_PORT, API_URL, AUTH_SECRET, CLIENT_ID, OIDC_PORT, OIDC_URL, SCHEMA, WEB_PORT, WEB_URL } from "./e2e-real/env";

// The real-stack tier: the production web build, the Quarkus API and PostgreSQL, with a local
// OIDC issuer standing in for Google. Needs a reachable PostgreSQL and a built API
// (`cd ../api && ./gradlew quarkusBuild`). See e2e-real/README.md.
const db = {
  host: process.env.E2E_DB_HOST ?? "localhost",
  port: process.env.E2E_DB_PORT ?? "5432",
  name: process.env.E2E_DB_NAME ?? "county_e2e",
  user: process.env.E2E_DB_USER ?? "county",
  password: process.env.E2E_DB_PASSWORD ?? "test-only",
};
const apiJar = process.env.E2E_API_JAR ?? "../api/build/quarkus-app/quarkus-run.jar";

export default defineConfig({
  testDir: "e2e-real",
  testMatch: "**/*.spec.ts",
  // Each spec file is one story on its own tenant, so files can run side by side.
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  use: { baseURL: WEB_URL },
  webServer: [
    {
      command: "node e2e-real/oidc-issuer.mjs",
      env: { OIDC_PORT: String(OIDC_PORT), OIDC_AUDIENCE: CLIENT_ID },
      url: `${OIDC_URL}/.well-known/openid-configuration`,
      reuseExistingServer: false,
    },
    {
      command: `java -jar ${apiJar}`,
      env: {
        QUARKUS_HTTP_PORT: String(API_PORT),
        QUARKUS_HTTP_HOST: "127.0.0.1",
        COUNTY_JDBC_URL: `jdbc:postgresql://${db.host}:${db.port}/${db.name}?currentSchema=${SCHEMA}`,
        COUNTY_DB_USER: db.user,
        COUNTY_DB_PASSWORD: db.password,
        QUARKUS_FLYWAY_SCHEMAS: SCHEMA,
        GOOGLE_CLIENT_ID: CLIENT_ID,
        COUNTY_PUBLIC_URL: WEB_URL,
        QUARKUS_OIDC_AUTH_SERVER_URL: OIDC_URL,
        QUARKUS_OIDC_DISCOVERY_ENABLED: "true",
        QUARKUS_OIDC_TOKEN_ISSUER: OIDC_URL,
      },
      url: `http://127.0.0.1:${API_PORT}/q/health/ready`,
      timeout: 120_000,
      reuseExistingServer: false,
    },
    {
      // Built here, not reused from an earlier `npm run build`: next.config.ts bakes
      // COUNTY_DEV_API_ORIGIN into the build, and this tier's API listens on another port.
      command: "npm run build && npm run start",
      env: {
        PORT: String(WEB_PORT),
        HOSTNAME: "127.0.0.1",
        AUTH_SECRET,
        AUTH_GOOGLE_ID: CLIENT_ID,
        AUTH_GOOGLE_SECRET: "e2e-real",
        COUNTY_API_URL: API_URL,
        COUNTY_DEV_API_ORIGIN: `http://127.0.0.1:${API_PORT}`,
      },
      url: `${WEB_URL}/healthz`,
      timeout: 180_000,
      reuseExistingServer: false,
    },
  ],
});
