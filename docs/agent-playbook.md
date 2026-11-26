# County continuation playbook — Itemcount-informed work queue

Audience: the next agent (or human) who picks up this repository.
Purpose: turn an analysis of the production Itemcount client into an ordered, testable work queue
that respects this repository's own rules.

---

## 0. How to use this document

1. Read, in this order: [`AGENTS.md`](../AGENTS.md) → [`api/README.md`](../api/README.md) →
   [`docs/superpowers/specs/2026-10-31-county-v1-functional-spec.md`](superpowers/specs/2026-10-31-county-v1-functional-spec.md)
   → this file.
2. The canonical specification is **`jasoncalalang/county-architecture`** (baseline
   `2d00e76319dbf9efd7832a4a36428000987ef39c`). The copies under `docs/superpowers/` may predate
   amendments. If a work item here contradicts an amendment, the amendment wins — stop and report the
   conflict instead of "fixing" the spec.
3. Take **one work item at a time**, top of the board first. Do not bundle items into one PR unless a
   work item says to.
4. Update the status board (§3) in your PR. That table is the shared state between agents.
5. Nothing here is authorization to deploy. `AGENTS.md`: "Do not merge or deploy without explicit
   authorization."

**Where the Itemcount evidence lives.** The analysis is **not part of this repository**. It sits beside the
author's checkout in `../itemcount-rea/` (`REPORT.md`, `analysis/api-surface.md`,
`analysis/graphql/viewer-fields.md`, `analysis/routes.txt`, `analysis/rest-paths.json`). On a fresh clone that
folder will not exist — that is expected, not a setup failure. **Appendix A carries every Itemcount fact the
work items rely on**, so no item is blocked by a missing folder: if you have it, read `REPORT.md` for context;
if you do not, the work items are still complete as written.

Itemcount is evidence of **product shape**, never a specification, and never a mandate to copy code. It was
analysed statically without credentials, so any statement about its *server* behaviour is unknown.

### 0.1 First hour on a fresh machine (Linux, cloud session or CI-like box)

Prove the baseline is green before changing anything. An agent that cannot run the tests will silently
violate "tests first".

```bash
# 1. toolchain
java -version                     # must be 21
node --version && npm --version   # web only: Node >= 20.9 AND npm are both required

# 2. database — pick the branch that matches the host
pg_ctlcluster 16 main start       # cloud session: installed but NOT running
pg_isready                        # expect: accepting connections

# 3. api baseline
cd api && COUNTY_TEST_SCHEMA=county_test_$(date +%s) ./gradlew build

# 4. web baseline (independent of the database)
cd ../web && npm ci && npm run lint && npm test && npm run build
```

**If the host has no system PostgreSQL** (`pg_ctlcluster` / `pg_isready` not found — common on a plain VM),
run the same image CI uses and point the suite at it:

```bash
docker run -d --name county-pg -p 5432:5432 \
  -e POSTGRES_USER=county -e POSTGRES_PASSWORD=test-only -e POSTGRES_DB=county_test \
  postgres:17-alpine

export COUNTY_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/county_test
export COUNTY_TEST_DB_USER=county
export COUNTY_TEST_DB_PASSWORD=test-only
cd api && COUNTY_TEST_SCHEMA=county_test_$(date +%s) ./gradlew build
```

Do not install or upgrade the JDK or Node to make a host fit. If the toolchain is wrong, report it and stop;
a local install silently diverges from CI.

Expected: `BUILD SUCCESSFUL` with the whole backend suite green, and `.next/standalone` written by the web
build. If `./gradlew` is not executable, `chmod +x api/gradlew` — do not substitute a system Gradle.

- **Postgres 16 here, 17 in CI.** Both must pass. Do not use syntax that only exists in 17; if you believe you
  need it, say so in the PR and expect the cloud session to fail.
- **`node` present is not enough.** Next 16 declares `engines.node >= 20.9.0` and `npm ci` is the only
  supported install; a host with an older Node or without npm cannot run the web checks.
- **Playwright browsers** are preinstalled only in the cloud session. On another Linux box run
  `cd web && npx playwright install chromium` once before `npm run e2e`.
- **Test schemas accumulate.** Every run needs a new `COUNTY_TEST_SCHEMA`; drop your own afterwards.
- **CI checks appear only for `api/**` changes** (`.github/workflows/backend.yml`). A docs-only PR legitimately
  shows no checks; an `api/**` PR must show the `Backend verification` job green. Never merge an API change on
  "no checks reported".

---

## 1. Non-negotiables (each is a check you can fail)

| Rule | Where it comes from | How you fail it |
|---|---|---|
| Tenant scope comes from the verified Google subject or the reseller token — **never** from a request field | `AGENTS.md` | Adding `tenantId` to any input record, path, or query parameter |
| Every endpoint change ships with isolation **and** validation tests | `AGENTS.md` | Merging a route change without a new `IsolationApiTest` case |
| Tests are written first | `AGENTS.md` | A PR whose tests could not have failed before the change |
| Flyway owns the schema; Hibernate never generates or alters it | `api/README.md`, `application.properties` (`schema-management.strategy=none`) | Editing an entity without a `V*__*.sql` migration |
| Migrations are append-only | Flyway convention | Editing `V1__ordering.sql` or `V2__owner_identities.sql` |
| Lock order is fixed: **reseller → business → products by ascending id → order** | `api/README.md` | A new workflow that locks in another order (deadlock) |
| API changes go through the contract: `./gradlew updateOpenApi`, inspect the diff, commit it; `verifyOpenApi` runs in `build` | `api/README.md` | Hand-editing `api/contract/openapi.yaml` without the generator, or letting drift through |
| Responses use typed DTOs (`Views`, `Inputs`), never database maps | `api/README.md` | Returning `Map<String,Object>` or a Panache entity |
| Never log bearer tokens, order-link tokens or mobile numbers | `api/README.md`, `application.properties` (log levels silenced for exactly this reason) | A `log.info` that interpolates a token; a new exception path that prints SQL values |
| Money is `numeric(12,2)` and never rounded silently | `Money.java` | Introducing a rounding mode without an ADR and tests |

---

## 2. Current state (measured at `3c425fa` — re-measure before trusting)

| | |
|---|---|
| Backend | Quarkus REST, Java 21, Postgres 17, Flyway. 25 main files / 1 332 lines, 14 test classes |
| API | 19 paths in `api/contract/openapi.yaml`; owner under `/api`, public under `/api/order/{token}` |
| Auth | Google ID token (OIDC, audience-checked); `external_identity(provider,subject)` → `app_user`; first `/me` provisions idempotently |
| Data | `business`, `product`, `reseller`, `purchase_order`, `order_line`, `app_user`, `external_identity` |
| Guarantees already implemented | pessimistic lock ordering; advisory lock on first sign-in; `(tenant_id, idempotency_key)` replay; back-button returns the same order |
| Web | **stub**: `web/app/page.tsx` (one `<h1>`) + `web/app/healthz/route.ts`. No UI, no Auth.js wiring |
| Known-honest gaps | no rate limiting, no audit trail, no pagination, no stock, no variants, no receipts |

Verification commands (from `CLAUDE.md` / `api/README.md`):

```bash
# backend (needs a real Postgres; no Docker, no Dev Services)
cd api && COUNTY_TEST_SCHEMA=county_test_$(date +%s) ./gradlew build

# web
cd web && npm ci && npm run lint && npm test && npm run build
cd web && npm run e2e -- --trace off
```

In a cloud session Postgres is **not running at start**: `pg_ctlcluster 16 main start`, then use the
provided `COUNTY_TEST_JDBC_URL`. Never point tests at production credentials.

---

## 3. Status board (edit this table in your PR)

| ID | Item | Priority | Status | Blocked by |
|---|---|---|---|---|
| W1 | Widen quantities + introduce units | P0 | not started | — (D2: ADR 0017) |
| W2 | Harden the public order surface | P0 | not started | — (D3: ADR 0018) |
| W3 | `order_event` (actor-level history) | P0 | not started | — |
| W4 | Rename `purchase_order` → `sales_order` | P0 | not started | — (D1: ADR 0016) |
| W5 | Web UI (owner screens + reseller page) | P0 | not started | W3 (order detail history) |
| W6 | Pagination on orders/products/resellers | P1 | not started | — |
| W7 | Minimal stock (`stock_on_hand`) | P1 | not started | W1 |
| W8 | Product variants | P1 | not started | W1, W7 |
| W9 | Delivery calendar depth | P2 | not started | — |
| W10 | Per-reseller pricing | P1 (ADR 0014) | done (county #4) | — |

`P0` = breaks the v1 promise or forces a data rewrite. `P1` = next slice. `P2` = explicit ceiling; do not
start without a named customer.

`V<next>` is the next free Flyway version on `main` when your PR is opened; check
`api/src/main/resources/db/migration/` and renumber if another PR merged first. Never reuse or reorder a
merged version.

ADR 0014 in `county-architecture` (accepted 2026-11-26) lifts the v1 scope freeze for five Beyond-v1
features in this order: price tiers, quotes, receive-with-discrepancy, MOQ + order windows, payment links.
W10 is the first of them. The other four are specified in the architecture repository's Beyond-v1 build
plan, not in this queue.

---

## 4. Work items

Each item lists **what to change**, **which tests to write first**, and **when it is done**. Use the exact
file names so parallel agents do not collide.

### W1 — Widen quantities and introduce units  *(P0, D2 decided: ADR 0017)*

**Why.** `order_line.quantity INTEGER CHECK (1..9999)` plus `product.unit IN ('pack','kg','pc')` cannot
express `1.5 kg`. A wholesaler of rice, meat or produce takes fractional orders. Itemcount models a
`MeasurementUnit` with conversions and keeps cost/selling units separately (`viewer.items.costMeasurementUnitId`,
`wacCostMeasurementUnitId`) — evidence that the unit is data, not an enum.

**Schema** — new migration `api/src/main/resources/db/migration/V<next>__fractional_quantities.sql`
- `order_line.quantity` → `NUMERIC(12,3)` with `CHECK (quantity > 0 AND quantity <= 9999)`.
- `product.unit` stays a label for compatibility; add `measurement_unit(id, tenant_id, code, label)` seeded
  with `pack`, `kg`, `pc` per tenant, and `product.measurement_unit_id` nullable FK backfilled from `unit`.
- Do **not** drop `product.unit` in this migration; deprecate it in a later one after W8.

**Code**
- `Inputs.Line.quantity`: `BigDecimal` with `@DecimalMin("0.001") @Digits(integer = 8, fraction = 3)`.
- `Money.line(BigDecimal price, BigDecimal quantity)`: multiply, then apply the rounding rule from **D2**;
  keep the `MAXIMUM` guard. Update the class comment — it currently promises exactness that fractions break.
- `Views.OrderLine.quantity`, `Views.ProductionLine.quantity`: `BigDecimal`; production run uses
  `sum(l.quantity)` (already SQL), which now yields numeric.
- `Views.CatalogProduct`, `Views.Product`: expose the unit code as today.

**Tests to write first**
- `domain/MoneyTest`: `120.00 × 1.5 = 180.00`; `120.00 × 0.333` follows D2 exactly; overflow rejected;
  three-decimal quantity accepted; four decimals rejected.
- `api/OrderingApiTest`: an order with `quantity: 1.5` round-trips through place/get/confirm and the
  production run sums to `1.5`.
- `api/IsolationApiTest`: fractional order from tenant A never appears in tenant B's production run.
- `api/ConcurrentOrderingTest`: two fractional orders for the same product still number sequentially.

**Done when** `./gradlew build` passes, `verifyOpenApi` shows only the intended diff, and a manual
`POST /api/order/{token}/orders` with `{"quantity":1.5}` returns `201` and a `line total` of `price × 1.5`.

**Do not** change `order_line.price` or `total` scale, and do not make `quantity` nullable.

### W2 — Harden the public order surface  *(P0, D3 decided: ADR 0018)*

**Why.** `POST /api/order/{token}/orders` is the only unauthenticated write. There is no rate limiting
anywhere, tokens never expire, and nothing caps junk orders. Itemcount's comparable surfaces ship a
reCAPTCHA site key and expiring invitation ids (`/partners/invitations/[invitationId]`).

**Schema** — `V<next>__reseller_token_lifecycle.sql`
- `reseller.token_expires_at TIMESTAMPTZ` (nullable; `NULL` = never expires, for backfill).
- Backfill: `UPDATE reseller SET token_expires_at = now() + interval '90 days'` (ADR 0018).

**API / code**
- New `api/RateLimits.java` as a `ContainerRequestFilter` bound to the reseller path, keyed by
  `sha256(token)` and client IP, fixed-window or token bucket. Config in `application.properties`
  (`county.rate-limit.place.permits`, `county.rate-limit.place.window`).
- `ResellerResource` catalog/place: treat `token_expires_at < now()` exactly like blocked — same
  `404` and the same "This link is no longer active" body. Do not add a new error shape.
- `POST /resellers/{id}/reissue-link`: sets a new token **and** a new expiry.
- Cap junk: reject more than `N` orders per token per day (`county.limits.orders-per-token-per-day`),
  returning the existing problem shape with a clear message.

**Tests to write first**
- `api/RateLimitApiTest`: the `N+1`-th placement inside the window returns `429`; an owner request in the
  same window is unaffected; a different token is unaffected.
- `api/IsolationApiTest`: an expired token behaves identically to a blocked token (`404`, same body).
- `api/ReviewRegressionTest`: reissue invalidates the old token and sets a future expiry.

**Done when** the limits are configurable, defaulted off in `%test` except in the dedicated test profile,
and `api/README.md` documents the new settings and the single-instance caveat of in-memory counters.

**Do not** put a captcha in the reseller flow without a product decision — county's reseller has no JS.

### W3 — `order_event`: actor-level history  *(P0)*

**Why.** Spec §3.4 promises "a history of status changes with timestamps"; the schema stores four
`*_at` columns and no actor. `OrderRules.Actor` already exists and is discarded. Roles (P2) are unsafe
without this.

**Schema** — `V<next>__order_events.sql`
```
order_event(id, tenant_id, order_id, at TIMESTAMPTZ, actor_type TEXT CHECK IN ('owner','reseller'),
            actor_user_id BIGINT NULL, actor_reseller_id BIGINT NULL, from_status TEXT, to_status TEXT,
            reason TEXT NULL)
```
Index `(tenant_id, order_id, at)`. No backfill from timestamps — leave history empty before W3 and say so
in the response.

**Code**
- `Orders.transition(...)` writes exactly one row in the same transaction, including the reseller-cancel path.
- `Views.Order` gains `List<Views.OrderEvent> events`; `GET /api/orders/{id}` and
  `GET /api/order/{token}/orders/{id}` include it (reseller sees their own events only).
- If a transition is rejected, **no** event row is written.

**Tests to write first**
- `api/OrderingApiTest`: confirm/deliver/mark-paid produce three events with the owner's user id.
- `api/ResellerApiTest` (new): reseller cancel records `actor_type=reseller` and their reseller id.
- `api/IsolationApiTest`: tenant B cannot see tenant A's events.
- A rejected transition (e.g. deliver a placed order) writes no row.

**Done when** `order_detail` can render history from the API alone, with actor names.

### W4 — Rename `purchase_order` → `sales_order`  *(P0, D1 decided: ADR 0016)*

**Why.** county's `purchase_order` is the reseller's order **to** the wholesaler — Itemcount's `SalesOrder`.
In accounting, a purchase order is what the business buys from a supplier (Itemcount: `PurchaseOrder`,
`supplier`, `sourcing`). When county adds sourcing, the name will mean the opposite thing.

**Schema** — `V<next>__rename_purchase_order.sql`
`ALTER TABLE purchase_order RENAME TO sales_order;` plus renamed constraints/indexes
(`sales_order_number_key`, `sales_order_delivery_idx`, `sales_order_reseller_recent_idx`) and the FK from
`order_line`. Keep the Java entity name (`PurchaseOrder.java`) **or** rename it in the same PR — either is
fine, but do not leave the two disagreeing; state the choice in the PR.

**Tests.** No behaviour change: the existing suite must pass untouched. Add one migration-level assertion in
`api/OpenApiContractTest` or a new `SchemaNamingTest` that `to_regclass('sales_order')` is present and
`purchase_order` is absent.

**Done when** `grep -ri purchase_order api/src` returns only intentional hits (e.g. an ADR reference).

### W5 — Web UI  *(P0, after W3)*

**Why.** `web/` is a stub; nothing validates the v1 spec end to end. Read `web/AGENTS.md` first — Next 16
differs from older App Router conventions, and the guidance file must not be reverted.

**Screens (from spec §3.1–3.6, §4.1–4.3)**
1. `/signin` — Auth.js Google; forward the **ID token** (not an access token) to the API.
2. `/setup` — business name, cut-off, delivery days.
3. `/orders` — date picker, status chips, summary line, row actions, 30 s refresh, stale-status message.
4. `/orders/[id]` — lines, note, paid, **event history (W3)**.
5. `/products`, `/resellers`, `/production-run`, `/settings`.
6. `/order/[token]` — public, server-rendered, **works with JavaScript disabled**.

**Constraints**
- Reseller page: no client JS required, no third-party scripts, `Referrer-Policy: no-referrer`,
  `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, CSP with `script-src 'self'` at most.
  Itemcount's customer-facing pages load GA4/GTM/PostHog — do not reproduce that on a token-bearing URL.
- Owner pages may be client components, but keep the session in an `HttpOnly` cookie. Do **not** copy
  Itemcount's `localStorage["auth-token"]` + `Authorization: Bearer` pattern.
- Every action must handle the stale-status case with the exact copy from spec §3.4.

**Tests to write first**
- `web/e2e/smoke.spec.ts` extended: reseller flow with `javaScriptEnabled: false` (Playwright context):
  open link → enter quantities → place → see confirmation → cancel a placed order.
- `web/app/order/[token]/route.test.ts`-style unit test asserting the security headers (or a Playwright
  response assertion).
- One owner-flow e2e with a seeded tenant (test-only API token path already used by `LocalOidcServer`).

**Done when** `npm run lint && npm test && npm run build && npm run e2e` pass, and the reseller e2e runs
with JS disabled.

### W6 — Pagination  *(P1)*

**Why.** `Orders.list(subject, date, status)` returns a `List` with no limit; `/products` and `/resellers`
return every row. Fine at 10 resellers, not at 300. Itemcount uses explicit pagination inputs everywhere
(`CommonPaginationInput`, `ItemPaginationInput`) — the lesson is to make it explicit and typed.

**API** — keyset (not offset): `?limit=` (default 50, max 200) and `?after=` opaque cursor encoding
`(placed_at, id)` for orders, `(lower(name), id)` for products/resellers. Response gains
`{"items":[…],"nextCursor":null|"…"}`; this changes `Views` — update the contract.

**Tests to write first.** Boundary: 0 rows, exactly `limit`, `limit+1`; cursor stability when a row is
inserted between pages; no duplicates; invalid cursor → `400` with the existing problem shape; tenant
isolation unaffected.

**Done when** `verifyOpenApi` diff is intentional and the web lists use the cursor rather than slicing in
memory.

### W7 — Minimal stock  *(P1, after W1)*

**Why.** The price list promises goods; without any stock notion the owner discovers overselling after
printing the run. Full lots/expiry is not needed — a number is.

**Schema** — `product.stock_on_hand NUMERIC(12,3) NULL` (`NULL` = not tracked).
**Code** — decrement inside the existing confirm transaction, under the **existing lock order** (products by
ascending id — already required by `api/README.md`); if any line exceeds available stock, reject the confirm
with the current problem shape and write nothing.
**Tests to write first** — `ProductOrderingConcurrencyTest` extended: two confirms racing for the last unit,
exactly one succeeds; untracked (`NULL`) products are unaffected; cancel does not restore stock (state that
decision in the PR; restoring is a separate item).

### W8 — Product variants  *(P1, after W7)*

Only with a named customer. Preferred shape: `product_variant(id, tenant_id, product_id, label, price)`, not
a second product row — Itemcount's `Product→Variant→VariantOption` is the reference. Every list and the
production run must group by variant, and `order_line` must snapshot `variant_label`.

### W9 — Delivery calendar depth  *(P2)*

Current rule is a weekday set plus one cut-off (`DeliveryDates.compute`). Real needs: holiday/blackout dates,
per-reseller delivery day, per-day cut-off. Itemcount reference for shape only: `UpdateRestrictedDayOfWeek`,
`UpdateFrequencyRoutes`, `deliveryStatus`. Do not start without a customer.

### W10 — Per-reseller pricing  *(P1, accepted by ADR 0014)*

Was deferred by spec §12; ADR 0014 lifts that. Built in county #4: `price_tier`, `product_tier_price` and
`reseller.price_tier_id`, resolved per line at placement and frozen on `order_line` like the base price.
Resellers on a deactivated tier keep its prices until reassigned (owner decision, 2026-11-26). Its
`price_tier` lock is the first entry in the ADR 0015 list in `api/README.md`. Itemcount reference: `itemPricingOption`, cascade pricing, `RistrictedPrice`.

---

## 5. Decision register (a human must answer before the item starts)

| ID | Decision | Answer (Jason, 2026-11-26) | Record |
|---|---|---|---|
| D1 | Rename `purchase_order` to `sales_order`? | Yes: one migration, no data change, Java names follow in the same PR | ADR 0016 |
| D2 | Rounding rule when quantity has three decimals | `HALF_UP` to centavos per line; order total = sum of rounded lines | ADR 0017 |
| D3 | Reseller link lifetime | 90 days; reissue gives a new token and a new 90 days; expired behaves exactly like blocked | ADR 0018 |
| D4 | BIR/receipts stance | No receipts, TIN, series or VAT flag while payment is a flag | ADR 0019 |

ADRs are in `county-architecture/adr/`. A new decision goes in this table with "open" in the Answer column
until a human answers it. Do not implement against a guessed answer.

---

## 6. Do-not-copy list (Itemcount patterns that would be regressions here)

| Pattern seen in Itemcount | Why it must not appear in county |
|---|---|
| JWT in `localStorage["auth-token"]`, `Authorization: Bearer` built in JS | XSS/extension exfiltration; no `HttpOnly` path. Keep the session in an `HttpOnly` cookie. |
| App document with no CSP / `X-Frame-Options` / `nosniff`; `access-control-allow-origin: *` | Token theft amplification. county already sends `no-store` + `no-referrer` globally via `PrivateResponses`; finish the header set on the web pages. |
| Entitlements evaluated client-side (`viewer.isAllowedToCancelTransaction`, `showCostColumn`) | Server must enforce; `OrderRules` + `Actor` already do — keep enforcement server-side and treat client flags as UX only. |
| 13 MB SPA before the sign-in form renders (9 scripts) | Defeats the no-JS, low-end-Android reseller requirement. county's HTML-first page is strictly better. |
| Two frontends during a migration (`LEGACY`/`PWA`, `frontend_switch`) | Two clients to patch; browser state cannot migrate. One client, one deploy. |
| GraphQL with no published contract and introspection blocked for anonymous callers | county has OpenAPI + drift tests — keep them green. |
| Polling replaced by socket subscriptions | Not needed yet; when it is, prefer SSE with the same stale-status semantics already specified. |

---

## 7. If you get stuck

- **Contradiction between this file and `county-architecture`** → the architecture repo wins; open an issue
  against it and leave the work item blocked.
- **A test needs a schema you cannot create** → stop, do not weaken the test to `@Disabled`; `api/README.md`
  requires the suite to fail when Postgres is unavailable.
- **A migration would need to rewrite existing rows** → check whether a backfill is enough; do not drop and
  recreate tables. County has no production data yet, but assume it might.
- **You are tempted to add `tenantId` to a request** → re-read §1; derive it from the subject/token instead.
- **The change is bigger than one work item** → split it and update the board; a reviewer must be able to
  read the PR against exactly one item above.

---

## Appendix A — Itemcount facts the work items rely on

Self-contained summary, so no work item depends on an external folder. These are static observations of the
shipped client `app.itemcount.io`, captured 2026-11-25; they describe **product shape only**.

| Fact | Value / shape | Used by |
|---|---|---|
| Product | Philippine multi-tenant inventory + POS + franchise ordering SaaS (warehouse, BIR-compliant receipts, HRIS, payments) | context |
| Client | Next.js static export on Vercel, 132 routes, 13 MB of JS, GraphQL via Apollo | context |
| API | single `viewer`-rooted GraphQL schema: **1 299 operations** (549 queries, 692 mutations, 58 subscriptions), **304 `viewer` root fields**, **74 named types / 1 126 selected fields**, 43 REST paths | W6, W10 |
| Tenancy | tenant implied by the bearer token; no `accountId` argument in most operations | §1 rule |
| Identity | Google OAuth, JWT in `localStorage`, roles `admin`/`mainAdmin`/`cashier`/`superadmin`/`employee`/`finance`, per-user permission matrix | W3, W5, §6 |
| Measurement | `MeasurementUnit` entity with conversions; items carry separate cost and selling units (`costMeasurementUnitId`, `wacCostMeasurementUnitId`) | W1 |
| Catalog | `Product → Variant → VariantOption`; per-channel prices (`priceApp`, `priceFoodpanda`, `priceLazada`); per-item pricing options and cascade pricing | W8, W10 |
| Stock | per-location counts (`locationCountsArray`), reorder points, lots with expiry, transfers, rejects | W7 |
| Orders | `SalesOrder` (out) vs `PurchaseOrder` (in), quotes, fulfilment orders, void requests, discrepancy reports | W4 |
| Delivery | `deliveryDate` / `expectedShipDate` / `deliveryStatus`, restricted weekdays, frequency routes | W9 |
| History | per-order status events separate from a platform audit trail (`/audit-trails/*`, `audit-logs` page) | W3 |
| Receipts | 11 `/transaction-report/*` endpoints: X-reading, Z-reading, e-journal, BIR sales report, sales adjustment | D4 |
| Scale habits | typed pagination inputs on essentially every list (`CommonPaginationInput`, `ItemPaginationInput`, …) | W6 |
| Integrations | Lazada, TikTok Shop, Xero, QuickBooks, MCP access keys — **out of scope for county** | not planned |

Two rules that follow from the table and are easy to get wrong:

1. Itemcount's `PurchaseOrder` is the **buying** side; county's current `purchase_order` is the **selling**
   side, which is Itemcount's `SalesOrder` (decision D1).
2. Itemcount evaluates entitlements client-side (`viewer.isAllowedToCancelTransaction`, `showCostColumn`) —
   county must keep enforcement server-side (§6).
