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

To run the end-to-end tests against a deployed environment instead of a
local build:

```bash
E2E_BASE_URL=https://county.example.ph npm run e2e
```

`@playwright/test` is pinned to an exact version so that it matches the
Chromium build already installed in Claude Code cloud sessions. On another
machine, install the matching browser once with
`npx playwright install chromium`.
