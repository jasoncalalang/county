// Ports, secrets and the schema name shared by the Playwright config and the tests. The values
// are for the real-stack tests only and protect nothing.
export const WEB_PORT = 3200;
export const API_PORT = 3299;
export const OIDC_PORT = 3298;
export const WEB_URL = `http://127.0.0.1:${WEB_PORT}`;
export const API_URL = `http://127.0.0.1:${API_PORT}/api`;
export const OIDC_URL = `http://127.0.0.1:${OIDC_PORT}`;
export const AUTH_SECRET = "e2e-real-only-secret-not-for-any-real-environment";
export const CLIENT_ID = "county-e2e-real";

// One fresh schema per run: Flyway creates and migrates it, nothing cleans it afterwards. The
// first process to load this file (the Playwright runner) picks the name and workers inherit it.
process.env.E2E_REAL_SCHEMA ??= `county_e2e_${Date.now()}`;
export const SCHEMA = process.env.E2E_REAL_SCHEMA;
