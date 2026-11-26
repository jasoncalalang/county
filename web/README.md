# county web

Next.js (App Router, TypeScript): the wholesaler dashboard and the reseller
order page.

```bash
npm ci
npm run dev      # http://localhost:3000
npm run lint
npm test         # Vitest
npm run build    # type-checks and writes the standalone server to .next/standalone
npm run start    # runs that standalone server, as the image does
npm run e2e      # Playwright against the production build of this checkout
```

Run `npm run build` before any push: Vitest does not type-check.

## Layout

| Path | What |
|---|---|
| `app/order/[token]/**/route.ts` | The reseller's order page: plain HTML from route handlers, no React runtime, works with JavaScript off. Rendering in `lib/order-page/`. |
| `auth.ts`, `app/auth/`, `proxy.ts` | Google sign-in with Auth.js at `/auth` (ADR 0002, ADR 0010). The session carries the Google ID token and refreshes it. |
| `app/(dashboard)/` | The wholesaler dashboard, client-rendered, calling `/api` with the ID token. |
| `app/setup/`, `app/page.tsx` | Set-up after the first sign-in, and the sign-in page. |
| `app/tokens.css` | Design tokens (UI design, section 3). The order page repeats them; a test keeps them equal. |

## Local development

Copy `.env.example` to `.env.local` and fill it in. The Google OAuth client needs the redirect
URI `http://localhost:3000/auth/callback/google`. Run the API on port 8080 (see
`api/README.md`) with the same `GOOGLE_CLIENT_ID`. Without an Ingress, `COUNTY_DEV_API_ORIGIN`
makes Next.js forward the browser's `/api` calls to the API; leave it unset in the cluster.

## End-to-end tests

`npm run e2e` starts `e2e/fake-api.mjs`, an in-memory stand-in for the API, and the production
build of this checkout, then runs the demo script of the functional spec (section 10). Google
sign-in cannot run in a test, so the tests mint an Auth.js session cookie with a test-only
secret. Set `E2E_SCREENSHOTS=<dir>` to save a screenshot of each screen.

To run the end-to-end tests against a deployed environment instead of a
local build:

```bash
E2E_BASE_URL=https://county.example.ph npm run e2e
```

`@playwright/test` is pinned to an exact version so that it matches the
Chromium build already installed in Claude Code cloud sessions. On another
machine, install the matching browser once with
`npx playwright install chromium`.
