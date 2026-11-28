# Plan: short-delivery (discrepancy) reports

Date: 2026-11-28. Work item: ADR 0014 feature 3, specified in the Beyond-v1 build plan §4
(`county-architecture/design/2026-11-23-beyond-v1-itemcount-build-plan.md`). ADRs: 0012
(orders are immutable; differences are a discrepancy report), 0013 and 0015 (lock order),
0017 (decimal quantities, half-up rounding). Builds on W3 and W1. Next free migration: `V8`.

## 1. Scope and non-goals

In scope (build plan §4, adjusted for W1's decimal quantities):

- A discrepancy report against a **delivered** order: what was actually received per product,
  filed by the reseller or by the owner, with an optional note. One report per order.
- The other party approves or declines it, with an optional note. The filer cannot resolve
  their own report.
- The report is a separate record. The order's lines, total, status and paid flag never
  change (ADR 0012). `shortfallTotal` is information only.
- API for both parties, an owner list of reports by status, and web screens: the reseller
  files, views and resolves on their order link (works without JavaScript); the owner files,
  views and resolves on the order detail page and has a list of open reports.

Decisions pinned here (each has a test):

- A submitted line must differ from what was ordered; a line with `received == ordered` is
  422. Products left out were received as ordered.
- Over-delivery is accepted: `received > ordered` gives a negative shortfall.
- Line shortfall = `(ordered − received) × captured order-line price`, rounded half up to
  centavos (sign kept). The report's shortfall is the sum of its lines.
- `receivedQuantity` follows W1: 0 to 9999, up to three decimals, fractions for `kg` only
  (the unit is the order line's captured unit).

Non-goals (YAGNI):

- No money effect: no credit, refund, receivable or change to `paid`.
- No reopen, no second report, no edit or withdrawal of a filed report.
- No photos or signatures, no notifications, no realtime (ADR 0004).
- No time limit on filing after delivery (open question 1).
- No discrepancy entries in `order_event`: the report carries who filed and who resolved.
- No discrepancy flag on order list or order views; existing views are unchanged.

## 2. Threat model

Assets: the record of what was delivered (it settles disputes between a business and a
reseller); each tenant's and each reseller's order data; the immutable order.

Actors: owner; reseller holding a link; anonymous caller; owner of another tenant.

Entry points: the nine endpoints in §3, and the web forms in front of them.

| # | Abuse case | Control | Test |
|---|---|---|---|
| C1 | Owner of tenant B reads, files on or resolves a report of tenant A | Order and report lookups are tenant-scoped from the verified subject; 404 | T12 |
| C2 | Reseller X reads, files on or resolves a report on reseller Y's order (same business) | Order lookup is scoped to the token's reseller; 404 | T13 |
| C3 | The filer approves their own report | Only the other party resolves; 409 | T7 |
| C4 | A report on an order that was never delivered | Only `delivered` orders accept one; 409 "Order is now <status>" | T5 |
| C5 | Several reports on one order, by repeat or by race | Order row lock plus `UNIQUE (tenant_id, order_id)`; 409 | T6, T16 |
| C6 | An idempotency key replayed by another reseller or on another order to read a report | Key matched only to a report of the same order and filer; otherwise 409 with no data | T4 |
| C7 | Probing for products through `productId` | A product not on the order is 422 whether or not it exists | T9 |
| C8 | Bad quantities or sizes: negative, above 9999, four decimals, fraction of a pack, note over 500, over 50 lines, duplicate product | Typed inputs with bounds; 422 keyed by JSON path | T9, T10 |
| C9 | The request supplies ordered quantity, price, shortfall, filer or resolver | Captured from the order line and the credential only; inputs carry none of them | T2, T11 |
| C10 | A report's lines or the report are changed or deleted later | No endpoint does; database trigger rejects `UPDATE`/`DELETE` on `discrepancy_line` and `DELETE` on `discrepancy_report`; resolution is the only update and is allowed once | T8, T15 |
| C11 | A blocked or unknown link uses the reseller endpoints | Same 404 as every reseller endpoint | T13 |
| C12 | Anonymous caller on owner endpoints | `@Authenticated`; 401 | T14 |
| C13 | The owner list leaks another tenant's reports | List is tenant-scoped | T12 |
| C14 | Markup in a note is rendered on the other party's screen | Reseller page uses the escaping `html` template; the dashboard renders text through React | W3 |
| C15 | A report changes what the reseller owes or the production record | The order is never written; test compares the order before and after | T3 |
| C16 | Over-delivery at a high price overflows the money column | Shortfall magnitude is checked against the `numeric(12,2)` ceiling; 422 | T10 |

## 3. Design

### Migration `V8__discrepancy_reports.sql`

`discrepancy_report(id, tenant_id → business, order_id, filed_by, filed_at, status
'open'|'approved'|'declined', note, resolution_note, resolved_by, resolved_at,
shortfall_total NUMERIC(12,2), idempotency_key UUID)` with FK `(tenant_id, order_id)` →
`sales_order`, `UNIQUE (tenant_id, order_id)`, `UNIQUE (tenant_id, idempotency_key)`,
`UNIQUE (tenant_id, id)`, a check that `resolved_by`/`resolved_at` are set exactly when the
status is not `open`, and a check that `resolved_by <> filed_by`.

`discrepancy_line(id, tenant_id, report_id, product_id, product_name, unit,
ordered_quantity NUMERIC(7,3), received_quantity NUMERIC(7,3) CHECK 0..9999, price
NUMERIC(12,2), shortfall_total NUMERIC(12,2))` with FKs to the report and to
`product(tenant_id, id)`, `UNIQUE (report_id, product_id)`.

Index `(tenant_id, status, filed_at DESC)`. Triggers per C10 (the report trigger also rejects
an `UPDATE` of a report that is no longer `open`). Postgres 16 and 17.

### API

Owner (`@Authenticated`, tenant from the subject), in new `api/OwnerDiscrepancyResource`:

- `POST /orders/{id}/discrepancy` file (201; 200 replay of the same key)
- `GET /orders/{id}/discrepancy` (404 when none)
- `POST /orders/{id}/discrepancy/approve`, `.../decline` (resolve a reseller-filed report)
- `GET /discrepancies?status=open|approved|declined` (status optional; newest first)

Reseller (tenant and reseller from the token), in new `api/ResellerDiscrepancyResource`:

- `POST /order/{token}/orders/{id}/discrepancy` file (201; 200 replay)
- `GET /order/{token}/orders/{id}/discrepancy`
- `POST /order/{token}/orders/{id}/discrepancy/approve`, `.../decline` (resolve an
  owner-filed report)

`Inputs.Discrepancy(lines: 1..50 of {productId, receivedQuantity}, note ≤ 500,
idempotencyKey)`, `Inputs.Resolve(note ≤ 500)` (body optional). `Views.Discrepancy`,
`Views.DiscrepancyLine`, `Views.DiscrepancySummary` as in build plan §4.6, with decimal
quantities through `Quantity.wire`, plus `resellerName` on the summary. No actor ids or
names: `filedBy` and `resolvedBy` are `owner` or `reseller`.

Code:

- `domain/DiscrepancyRules`: `Status` enum, who may resolve (the other party, only while
  open), and `shortfall(ordered, received, price)` (half up, sign kept, ceiling checked).
  Pure, unit-tested.
- `persistence/DiscrepancyReport`, `DiscrepancyLine` (entities) and
  `persistence/Discrepancies` (service): one method per action (`fileByOwner`,
  `fileByReseller`, `approveByOwner`, `declineByOwner`, `approveByReseller`,
  `declineByReseller`, `forOwner`, `forReseller`, `list`). Injects `Clock`. Nothing is added
  to `Orders`, `Owners`, `OwnerResource` or `ResellerResource`.
- Locks: reseller paths lock the reseller, then the order; owner paths lock the order. Every
  write happens under the order's write lock, so the report is never locked on its own and
  is not a lock target under ADR 0015 (say so in `api/README.md`). Products are not locked:
  lines copy from `order_line`. The idempotency key is checked under the order lock.
- OpenAPI: nine new operations and the new schemas. No existing schema changes.

### Web

- `web/lib/discrepancy.ts` (pure, tested): build the file input from a form (reuses
  `parseQuantity`; only lines that differ are sent), labels for status and parties, shortfall
  display.
- Reseller (`web/lib/order-page/`, route files under `web/app/order/[token]/orders/[id]/`):
  a delivered order shows "Report a problem with this delivery" or the report's status;
  `discrepancy` page with the form (received quantity per line, note) or the report;
  approve and decline posts with a confirmation step like cancel. No JavaScript needed;
  HTML only through the escaping template.
- Owner (`web/app/(dashboard)`): the order detail page shows the report with approve and
  decline, or a "Report a short delivery" form for a delivered order; a `/discrepancies` page
  lists open reports and links to the orders; a nav entry.
- `web/e2e/fake-api.mjs` implements the nine endpoints with the same rules.

### Docs

`api/README.md` (endpoints, rules, lock note), playbook: note under ADR 0014 that feature 3
is built.

## 4. Test list

| # | Behaviour | Class | Fails first because |
|---|---|---|---|
| T1 | `DiscrepancyRules.shortfall`: `(10 − 8) × 120.00 = 240.00`; `(1.5 − 1.25) × 99.99 = 25.00` (half up); over-delivery `(10 − 12) × 120 = −240.00`; equal quantities refused; magnitude over the ceiling refused; who may resolve | `domain/DiscrepancyRulesTest` (new) | stub |
| T2 | Reseller files one short line on a delivered order: 201; body has captured ordered, received, price, line and total shortfall, `filedBy=reseller`, `status=open`; `GET` returns the same to reseller and owner | `api/DiscrepancyApiTest` (new) | endpoint stub returns nothing |
| T3 | Filing and resolving leave the order's total, status, paid, lines and events exactly as before | same | as T2 |
| T4 | Same key, same order, same filer: 200 with the same report and no second row. The key from another reseller's report, or on another order: 409 and no report data in the body | same | as T2 |
| T5 | Filing on a placed, confirmed or cancelled order: 409 "Order is now <status>" | same | as T2 |
| T6 | A second report on the order with a new key: 409 | same | as T2 |
| T7 | Owner approves a reseller-filed report (200, `resolvedBy=owner`, note stored); owner declines; reseller approves an owner-filed report; the filer resolving their own is 409; resolving twice is 409 | same | as T2 |
| T8 | Resolution changes only status, resolver, time and resolution note; lines are unchanged | same | as T2 |
| T9 | 422 keyed by path: product not on the order (existing or not, same answer), duplicate product, received equal to ordered, negative, above 9999, four decimals, fraction of a pack, empty lines, 51 lines, missing key | same | as T2 |
| T10 | Note over 500 and resolution note over 500 are 422; over-delivery whose shortfall exceeds the ceiling is 422 | same | as T2 |
| T11 | Extra body fields naming price, ordered quantity, `filedBy` or shortfall are ignored: stored values come from the order and the credential | same | as T2 |
| T12 | Tenant B's owner: 404 on get, file, approve and decline for tenant A's order; B's `GET /discrepancies` is empty while A's lists the report | `api/DiscrepancyIsolationTest` (new) | as T2 |
| T13 | Reseller X's link: 404 on get, file, approve and decline for reseller Y's order; a blocked link: 404 on all four | same | as T2 |
| T14 | Owner endpoints without a token: 401 | same | passes once routes exist; guard |
| T15 | Database rejects `UPDATE`/`DELETE` of a line, `DELETE` of a report and an `UPDATE` of a resolved report | `api/DiscrepancySchemaTest` (new) | no trigger |
| T16 | Two concurrent filings on one order with different keys: one 201, one 409, one report | `api/DiscrepancyConcurrencyTest` (new) | as T2 |
| T17 | `GET /discrepancies` lists newest first with order number and reseller name, filters by status, and rejects an unknown status with 422 | `api/DiscrepancyApiTest` | as T2 |
| W1 | `web/lib/discrepancy.ts`: only differing lines are sent; bad quantities give per-line messages; nothing differing gives a form message; labels | `web/lib/discrepancy.test.ts` (new) | stub |
| W2 | Reseller handlers: delivered order page offers the report; filing redirects to the report; API 409 and 422 re-render with messages; approve and decline of an owner-filed report | `web/lib/order-page/discrepancy.test.ts` (new) | handlers missing |
| W3 | A note containing `<script>` is escaped on the reseller's report page | same | guard; may pass when written |
| W4 | e2e, JavaScript off: reseller reports 8 of 10 received; owner sees it in the open list, opens the order, approves; reseller sees "Approved" | `web/e2e/demo.spec.ts` | pages missing |
| W5 | e2e: owner files a report from the order page; reseller declines it on the link | same | pages missing |

## 5. Commit sequence

1. `docs: plan short-delivery reports` (this file, alone).
2. Cycle 1, rules: `test:` T1 → `feat:` → `refactor:` or reason.
3. Cycle 2, file and read: `test:` T2–T6, T9–T11 → `feat:` V8 without triggers, entities,
   service, both resources (file and get), OpenAPI → `refactor:`.
4. Cycle 3, resolve and list: `test:` T7, T8, T17 → `feat:` → `refactor:` or reason.
5. Cycle 4, isolation, schema, concurrency: `test:` T12–T16 → `feat:` triggers (added to the
   unmerged V8) and anything the tests expose → `refactor:` or reason. Say in the commit body
   which of these already pass.
6. Cycle 5, web reseller: `test:` W1–W3 and the reseller half of W4 → `feat:` → `refactor:`.
7. Cycle 6, web owner: `test:` rest of W4, W5 → `feat:` → `refactor:` or reason.
8. `docs:` README and playbook.

## 6. Open questions

For Jason in review; none blocks the build:

1. Should filing close some days after delivery? Not built; today a report can be filed on
   any delivered order.
2. An approved report has no money effect. If an approved shortfall should reduce what the
   reseller owes, that is the payments work (ADR 0014 feature 5), not this item.
