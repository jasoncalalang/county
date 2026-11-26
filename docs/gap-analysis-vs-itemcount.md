# county — gap analysis against the reverse-engineered Itemcount client

> **This document is background, not instructions.** The normative, ordered work queue for agents is
> [`agent-playbook.md`](agent-playbook.md); if the two disagree, the playbook wins. Use this file for the
> comparison tables and the reasoning behind each work item.

**Subject:** [`jasoncalalang/county`](https://github.com/jasoncalalang/county) @ `3c425fa` (2026-11-23)
**Reference:** reverse-engineered production client `app.itemcount.io` (`../itemcount-rea/REPORT.md`)
**Scope note:** county is a deliberately narrower product (wholesaler ↔ reseller ordering), and its
[v1 spec §12](superpowers/specs/2026-10-31-county-v1-functional-spec.md) already defers POS, BIR,
branches and accounting "out of scope by the design". This document separates three different things:

- **P0 — breaks county's own v1 promise, or forces a rewrite of data already in the database.**
- **P1 — the next slice; Itemcount is evidence for the shape, not for the scope.**
- **P2 — explicit ceilings.** Decide and record them; do not drift into them.

Nothing here argues that county should become Itemcount.

---

## 1. What county is today (measured, not read from the spec)

| | |
|---|---|
| Backend | Quarkus REST, Java 21, Postgres 17, Flyway. **25 main files / 1 332 lines**, 14 test files |
| API | **19 paths** (`api/contract/openapi.yaml`), OpenAPI drift test, typed DTOs, `no-store` |
| Auth | Google ID-token verification (OIDC); tenant from `external_identity(provider,subject)` → `app_user` |
| Data | `business`, `product`, `reseller`, `purchase_order`, `order_line`, `app_user`, `external_identity` |
| Concurrency | pessimistic locks in a fixed order; advisory lock on first sign-in; `(tenant_id, idempotency_key)` unique; back-button replay returns the same order |
| Public surface | `GET /api/order/{token}`, `POST /api/order/{token}/orders`, read/cancel own order |
| Web | **stub** — `app/page.tsx` (one `<h1>`) + `app/healthz/route.ts`. No UI, no Auth.js wiring |

Reference scale on the other side: Itemcount ships **132 routes / 1 299 GraphQL operations / 304 `viewer` fields / 74 types with 1 126 selected fields / 58 subscriptions / 43 REST paths**.

---

## 2. Capability matrix (ordering-relevant rows first)

| Capability | county today | Itemcount (evidence) | Gap? |
|---|---|---|---|
| Price list, per-tenant | `product` name/unit/price, active toggle | `Item`→`Product`→`Variant`→`VariantOption`, `itemPricingOption` | **variants missing** |
| Quantities | `INTEGER 1..9999`, unit enum `pack` / `kg` / `pc` | `MeasurementUnit` entity + conversions (`costMeasurementUnitId`, `wacCostMeasurementUnitId`) | **P0 — cannot sell 1.5 kg** |
| Price snapshot on lines | `order_line.product_name/unit/price/total` | same pattern (`salesOrderLineItem` copies `price`, `description`) | none — parity |
| Delivery date from cut-off | `DeliveryDates.compute` (server, business tz) | `deliveryDate`, `expectedShipDate`, `UpdateRestrictedDayOfWeek`, `UpdateFrequencyRoutes` | partial (no holidays, per-reseller days) |
| Order lifecycle | placed→confirmed→delivered/cancelled, paid flag | `Transaction`/`SalesOrder` + `voidRequest`, `discrepancy`, `fulfillmentOrder` | partial |
| Production run | per-product totals for a date | `production` module + BOM (`billOfMaterials`, `parentBom`) | partial (no recipe/consumption) |
| Stock / lots / expiry | none (deferred) | `locationCountsArray`, `lotItems`, `ExpiringLotItemPerAccount`, `ItemReorderPointReached` | **P1** |
| Multi-user / roles | one owner | 6 roles + `accountPermissions.permissions` + per-tab perms | P1 |
| Branches / locations | none | `Account→BusinessGroup→Branch→InventoryLocation` | P1 |
| Audit trail | timestamps only (no actor, no event table) | `/audit-trails/{generate,signout}`, `audit-logs` route, `audit-trail` entity | **P0 for multi-user** |
| Search / pagination | none (`GET /orders?date=&status=`; 0 page/limit params) | 333 input types incl. `CommonPaginationInput`, `ItemPaginationInput`; `/search/v1/api` | P1 |
| Realtime | 30 s polling | 58 WS subscriptions + invalidation bus (`ForceReloadByAccount`) | P2 (fine for v1) |
| Payments | `paid` boolean | GCash/Maya/GrabPay taxonomy, Xendit links, PayPal, `payment`, `remittance` | P2 ceiling |
| Receipts / BIR | none | `/transaction-report/{x-reading,z-reading,e-journal,bir-sales-report}`, `tin`, `isVatExempt`, `serialNumber` | P2 ceiling |
| Notifications | manual copy-paste | Knock feeds, SMS credits, `NewNotification`, `NewGroupSalesOrder` | P2 |
| Exports | print-only production run | 10+ report exports + CSV importer (`usecsv`) | P2 |
| Integrations | none | Lazada, TikTok Shop, Xero, QuickBooks, AI/MCP keys | out of scope |
| Abuse controls on public writes | **none** (no rate limit, no captcha, no token expiry) | reCAPTCHA site key shipped on auth; expiring invitation ids | **P0** |

---

## 3. P0 — fix before the next feature

### 3.1 Fractional quantities are unrepresentable

`order_line.quantity INTEGER CHECK (quantity BETWEEN 1 AND 9999)` and `product.unit IN ('pack','kg','pc')`.
A Philippine wholesaler selling rice, meat or produce takes **1.5 kg**, **0.25 kg**, **2.75 kg** orders.
With integer quantity, the only options are to lie about the unit (price per 100 g and make the reseller
do arithmetic) or to refuse the order.

Itemcount models this as a first-class `MeasurementUnit` with conversions and keeps both a cost unit and a
selling unit on the item; its POS lines carry `quantity` plus unit ids. That is the shape county needs, and
it is cheap **now** (a `numeric(12,3)` quantity column + a `measurement_unit` table) and expensive later
(every historical line, every production-run total, every printed order).

Minimum viable change: `quantity NUMERIC(12,3)`, keep `unit` as a label, and add a `unit_factor` (or a
`measurement_unit(id, code, base_code, factor)`) so `1.5 kg` and `1500 g` can coexist later.

### 3.2 The only public write endpoint has no abuse control

`POST /api/order/{token}/orders` is unauthenticated by design (reseller has no account). Today:

- `token VARCHAR(22)` — no expiry, no attempt limit, no rate limit anywhere in the API
  (`api/README.md`: "No … rate limiting … is included").
- A 22-char token is not guessable, but it is a **bearer secret in a URL**: it lands in Viber/Messenger
  previews, browser history, and any analytics that touch the URL. `reissue-link` exists, which is the right
  control — but nothing forces or schedules it.
- No captcha/honeypot, so a leaked link can be scripted into thousands of junk orders, each one a real row
  and a production-run entry.

Itemcount's equivalent lessons: its auth surface ships a **reCAPTCHA site key**
(`NEXT_PUBLIC_GOOGLE_RECAPTCHA_SITE_KEY`) and it uses **expiring invitation ids**
(`/partners/invitations/[invitationId]`) rather than permanent links for anything consequential.

Minimum: per-token and per-IP rate limit on `POST /order/{token}/orders`, `Referrer-Policy: no-referrer`,
token expiry (`expires_at`, default 90 days, reissue extends), and a per-token order/day cap that returns a
clear "contact your supplier" message.

### 3.3 No actor or event history

Spec §3.4 promises "a history of status changes with timestamps". The schema stores only
`confirmed_at / delivered_at / cancelled_at / paid_at` — no actor, no reason, no event row. That is derivable
today, but the moment staff confirm orders (P1) the history is unattributable, and `OrderRules.Actor` already
exists in code with nowhere to persist it.

Add `order_event(id, tenant_id, order_id, at, actor_type, actor_id, from_status, to_status, reason)` and write
one row per transition in the same transaction. Itemcount's `/audit-trails/generate` and `audit-logs` page are
the analogue at platform scale; county only needs the per-order version.

### 3.4 `purchase_order` is the wrong name and will collide

County's `purchase_order` is the **reseller's order to the wholesaler** — i.e. Itemcount's **`SalesOrder`**.
In Itemcount (and in accounting generally) a *purchase order* is what the business buys **from a supplier**
(`PurchaseOrder`, `purchaseOrderLineItem`, `supplier`, `sourcing`). When county adds the supplier side — and
§12 already lists stock and sourcing-adjacent work — `purchase_order` will mean two opposite things.

Rename now while there is one migration: `sales_order` (or `reseller_order`) + `purchase_orders` reserved for
the buying side.

### 3.5 The web app does not exist yet

`web/` is `app/page.tsx` + `healthz`. The v1 spec needs roughly ten screens (sign-in, set-up, orders list,
order detail, products, resellers, production run, settings, reseller ordering page, confirmation).
Itemcount's 132-route map is a useful *checklist of screens a PH ordering product accumulates* — but the
reseller-facing subset is small and matches county's §4 exactly.

This is not a design gap, only sequencing; it is listed as P0 because nothing can be validated end-to-end
until it exists, and because the reseller page's "no-JS, low-end Android" constraint should be proven before
more backend is built.

### 3.6 Public URL already carries a secret; make that explicit and safe

`web/README.md` / spec require `https://county.example.ph/order/{token}`. The API is already covered —
`PrivateResponses` is a global `ContainerResponseFilter` that sets `Cache-Control: no-store` and
`Referrer-Policy: no-referrer` on **every** response, including the public ones. What remains:

- the **Next.js route** serving `/order/{token}` must send `Referrer-Policy: no-referrer` too (a response
  filter on the API does not protect the HTML document), plus `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY`, and a CSP with no third-party scripts;
- **no analytics on that page.** Itemcount's equivalent customer-facing page loads GA4, Google Tag Manager and
  PostHog — do not put a token-bearing URL in front of those;
- do not let the token reach any log line (`api/README.md` already says this — enforce it with a test, e.g. a
  log-capture assertion on the 404 path).

---

## 4. P1 — the next slice, shaped by Itemcount evidence

| # | Gap | Why it is next | Itemcount evidence to copy the *shape* from |
|---|---|---|---|
| 4.1 | **Availability / stock** | The price list promises goods. Without any stock notion, the confirming owner discovers oversell after printing the run. Even `stock_on_hand` + decrement on confirm + a "low stock" flag beats nothing. | `locationCountsArray`, `reorderPoint`, `ItemReorderPointReached`, transfers |
| 4.2 | **Variants** | "Hamonado 500 g / 1 kg" today means two products and a duplicated price edit. | `Product→Variant→VariantOption`, `variantOptions` on lines |
| 4.3 | **Per-reseller pricing / tiers** | The moment a wholesaler has 20 resellers, someone gets a better price. This is the top real-world request and it changes `product.price` from a column to a resolved price. | `itemPricingOption`, `*-cascade-pricing` (localStorage), `UpdateBusinessGroupMembersRevenueOpsCustomPricing`, `RistrictedPrice` |
| 4.4 | **Pagination + search** | `GET /orders?date=&status=` returns everything for that date; products/resellers return all rows. Fine at 10 resellers, not at 300. | `CommonPaginationInput`/`ItemPaginationInput`, `/search/v1/api`, `SearchBusinessGroups` |
| 4.5 | **Branches / pickup points** | A wholesaler with two warehouses cannot say which one an order belongs to; delivery days and cut-off are per business, not per branch. | `Branch`, `InventoryLocation`, `branches-settings` |
| 4.6 | **Delivery calendar depth** | Current rule = weekday set + single cut-off. Real PH wholesale needs holidays/blackouts, per-reseller delivery days, and per-day cut-offs (e.g. Saturday closes earlier). | `UpdateRestrictedDayOfWeek`, `UpdateFrequencyRoutes`, `deliveryStatus`, `expectedShipDate` |
| 4.7 | **Notifications without SMS** | Manual paste is correct for v1. The next step is not an SMS gateway — it is a single outbound channel (email or webhook) for "new order" / "confirmed" / "cancelled". | `NewNotification`, `NewGroupSalesOrder` subscriptions; Knock feed ids (pattern only) |
| 4.8 | **Export** | The production run print is the only output. The natural next two are orders CSV and a per-reseller statement. | `/transaction-report/*` family, `/items/beginning-inventories` CSV flows |

---

## 5. P2 — explicit ceilings (record the decision, do not drift)

| Ceiling | What triggers it | What Itemcount shows it costs |
|---|---|---|
| **BIR / receipts** | county records money as `paid` but issues no document. The day a wholesaler needs a reseller-facing sales invoice, PH rules apply: registered series, VAT breakdown, Z-reading, e-journal. | 11 `/transaction-report/*` endpoints, `GenerateXReading/GenerateZReading/GenerateEJournal`, `tin`, `isVatExempt`, `privilegeDiscountPortion`, `serialNumber` |
| **Multi-user roles** | Staff confirming/delivering. Requires the P0 event log first, then a permission matrix. | 6 roles + `accountPermissions.permissions` + `updateItemDetailsTabsPermission` |
| **Realtime** | Kitchen/production screen needs < 5 s updates; 30 s polling is visible there. | 58 subscriptions + invalidation bus (`ForceReloadByAccount`, `RealtimeInvalidations`) |
| **Payments beyond a flag** | GCash/Maya references, partial payments, receivables. | payment-method taxonomy, `XenditLink`, PayPal buttons, `payment`/`remittance`/`disbursement` |

---

## 6. Model traps visible only from the reference

1. **Tenant concept is one level short.** county `business` = Itemcount `Account`. Itemcount adds
   `BusinessGroup` (a franchise/consortium of businesses) and `Branch`. If county ever serves a group of
   wholesalers, `tenant_id` is not enough — but do **not** add it speculatively; just keep `tenant_id` on every
   row (county already does) so a group layer can be introduced without backfill.
2. **`unit` as an enum is a dead end** (see 3.1). Itemcount's `measurementUnits` list is tenant-editable
   (`CreateMeasurementUnit`), which is what a wholesaler actually wants ("sako", "tray", "bundle").
3. **Status history is not audit.** Itemcount separates `itemHistoryPayload`/`transactionStatusUpdate` from
   `audit-trails`. county needs the actor-level version before roles.
4. **Public-link identity vs account identity.** county: token ⇒ reseller. Itemcount: `x-temporary-customer-id`
   for anonymous customers **and** real accounts. Keep the token model, but plan a `customer_id` nullable FK on
   `reseller` so a reseller can later become an account without rewriting orders.
5. **Idempotency scope.** county's uniqueness is `(tenant_id, idempotency_key)`. Itemcount's client retries per
   screen; if county later allows *draft → submit* across days, confirm the key is per submission attempt, not
   per browser session.

---

## 7. What county should **not** copy from Itemcount

| Itemcount pattern | Why not | county's better position |
|---|---|---|
| JWT in `localStorage["auth-token"]`, `Authorization: Bearer` from JS | XSS/extension exfiltration; no `HttpOnly` path | OIDC verified server-side; keep the session in an `HttpOnly` cookie |
| No CSP / `X-Frame-Options` / `nosniff` on the app document; `access-control-allow-origin: *` | Token theft amplification | county already sends `no-store` + `no-referrer`; finish the header set |
| Client-evaluated entitlements (`viewer.isAllowedToCancelTransaction`, `showCostColumn`, …) | The server must still enforce; the client copy is UX only | `OrderRules` + `Actor` enforce transitions server-side — keep it that way |
| 13 MB SPA for a reseller on a low-end Android (9 scripts before the sign-in form renders) | Defeats the "no-JS, cheap phone" requirement of county §4 | county's HTML-first reseller page is strictly better |
| Dual frontend migration (`LEGACY`/`PWA`, `frontend_switch`, deferred moves) | Two clients to patch, local state that cannot migrate | one client, one deploy |
| GraphQL schema with no introspection for anonymous callers and 500s for missing tokens | Hard to contract-test | county has OpenAPI + drift test — keep the contract test |

---

## 8. Where county is already ahead

- **Tenant scoping from the verified subject, never from request fields**, with isolation tests. Itemcount
  achieves the same with a `viewer`-rooted schema, but it is implicit in a token and not testable from outside.
- **Server-computed delivery date** with an explicit business timezone and cut-off — Itemcount's client also
  gets server dates, but county's rule is isolated in `DeliveryDates` and unit-tested.
- **Idempotent order submission** (`(tenant_id, idempotency_key)` + replay returns the same order). Itemcount's
  client has retry/duplicate handling in UI code; county makes it a database guarantee.
- **Contract-first API** (versioned OpenAPI + `verifyOpenApi` drift check). Itemcount has no published contract
  and blocks schema introspection.
- **Money as `numeric(12,2)` with `RoundingMode.UNNECESSARY`** — refuses to silently round a submitted price.
- **Small surface**: 19 endpoints vs 1 299 operations; far easier to keep correct.

---

## 9. Suggested order of work

1. **Rename + widen quantities** (`sales_order`, `numeric(12,3)` quantity, `measurement_unit` table) — one
   migration, before any real orders exist.
2. **Public-surface hardening** (rate limit, token expiry, per-token order cap, referrer policy) — before the
   link is pasted into Viber by real users.
3. **`order_event` + actor** — before the second user exists.
4. **Web UI** (six owner screens + the reseller HTML page) — proves the v1 spec end-to-end.
5. **Pagination + search** on orders/products/resellers — cheap, prevents a list-size rewrite.
6. **Stock flag (not full inventory)** — `stock_on_hand` + decrement on confirm; defer lots/expiry until a
   customer asks.
7. Then choose: variants + per-reseller pricing (commercial pressure) or branches (operational pressure).

Everything in §5 stays out until a named customer forces it; the point of listing them is that each has a
concrete migration cost visible in the reference product.

---

### Provenance

- county facts: this repository at `3c425fa` (files cited inline).
- Itemcount facts: `../itemcount-rea/REPORT.md`, `analysis/api-surface.md`,
  `analysis/graphql/viewer-fields.md`, `analysis/routes.txt`, `analysis/rest-paths.json`, and browser-capture
  Evidence `ev_e511a6a4f1190cedd48731b35402bc8328a007051a8f4a401fe4ec3c454e9790`.
- Itemcount was observed statically (no credentials); any statement about its *server* behaviour is marked as
  unknown in the report. This analysis therefore uses Itemcount as evidence of **product shape**, not of
  correctness.
