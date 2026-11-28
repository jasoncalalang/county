# Real-stack end-to-end tests

The second tier of web tests. `e2e/` runs the web build against `fake-api.mjs` and is fast.
This tier runs the production web build, the real Quarkus API and PostgreSQL together, so
it catches drift between the fake API and the real one, and exercises the API's token
verification and subject-to-tenant resolution.

| Process | Port | What |
|---|---|---|
| `oidc-issuer.mjs` | 3298 | Local OpenID Connect issuer standing in for Google. Fresh RSA key per run. |
| `api/build/quarkus-app/quarkus-run.jar` | 3299 | The real API, pointed at the issuer and at a fresh schema. |
| `npm run start` | 3200 | The production web build, calling the API. |

## Run it

```bash
pg_ctlcluster 16 main start                    # cloud sessions; PostgreSQL must be reachable
(cd ../api && ./gradlew quarkusBuild -x test)  # builds build/quarkus-app
npm run e2e:real -- --trace off
```

The database defaults to `county_e2e` on `localhost:5432`, user `county`, password
`test-only`. Override with `E2E_DB_HOST`, `E2E_DB_PORT`, `E2E_DB_NAME`, `E2E_DB_USER` and
`E2E_DB_PASSWORD`; `E2E_API_JAR` points at a jar elsewhere, and `E2E_PREBUILT=1` skips the
web build when you already ran `npm run build`. Every run creates a schema named
`county_e2e_<timestamp>` and never drops it, as the API tests do.

## Sign-in

Google cannot run headless. The tests mint an Auth.js session cookie whose ID token the
local issuer signed (`support.ts`), and the API verifies it against the issuer's discovery
document and JWKS: signature, issuer, audience and expiry are checked for real, and the
subject resolves to an owner through `external_identity`. **Not covered: Google itself**, the
OAuth redirect, consent, and the refresh-token exchange in `auth.ts`.

## Specs

Each file is a story on its own owner, so tenants never share data.

- `demo.spec.ts`: the functional spec section 10 demo script, the settings preview, and the
  no-JavaScript order page with cancel.
- `quotes.spec.ts`: draft, send, accept, decline, and the owner seeing the order.
- `isolation.spec.ts`: two owners, one UI each, and guessed ids.
