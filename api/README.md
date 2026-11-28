# County backend

Initial backend slice. Java 21 **JDK**, Gradle wrapper and a reachable PostgreSQL 17
are required. No Docker or Quarkus Dev Services are required or started.

## Development

Create a local development database and separate disposable test database, owned by
a local development role. Supply credentials in your shell, never commit them:

```sh
export COUNTY_JDBC_URL=jdbc:postgresql://localhost:5432/county
export COUNTY_DB_USER=county
export COUNTY_DB_PASSWORD=your_local_password
export GOOGLE_CLIENT_ID=your_development_google_client_id
export COUNTY_PUBLIC_URL=http://localhost:3000
./gradlew quarkusDev
```

Google OIDC verifies ID tokens and their audience. Owner calls use `Authorization:
Bearer <Google ID token>`. There is no development authentication bypass in production
code. Tests use Quarkus test-security annotations; this does not verify a live Google
OAuth browser flow. The future Next.js/Auth.js web app owns OAuth sign-in.

## Verification

```sh
export COUNTY_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/county_test
export COUNTY_TEST_DB_USER=county
export COUNTY_TEST_DB_PASSWORD=your_local_test_password
export COUNTY_TEST_SCHEMA=county_test_$(date +%s)
./gradlew test build
```

Choose a unique schema per integration run. Flyway creates and migrates it; tests
never clean a database. Remove disposable schemas afterwards using your local DB
admin tools. The integration suite must fail if PostgreSQL is unavailable; it does
not silently skip. Tests do not use production credentials or an external Google login.

For domain-only tests: `./gradlew test --tests 'ph.com.ridgewell.county.domain.*'`.

## API

Root `/api`; health `/q/health`, OpenAPI `/q/openapi`. Generated OpenAPI is written
under `build/openapi` during build; the versioned contract is `contract/openapi.yaml`.
`./gradlew verifyOpenApi` checks drift and runs as part of `check`/`build`. After an
intentional API change, run `./gradlew updateOpenApi`, inspect and commit its diff.

Owner: `/me`, `POST /tenants`, `GET/PUT /settings`,
`GET /settings/preview?cutoff=HH:mm&deliveryDays=1&deliveryDays=4`, `GET/POST /products`,
`PUT /products/{id}`, `GET/POST /resellers`, `PUT /resellers/{id}`,
`POST /resellers/{id}/{block|unblock|reissue-link}`, `GET /orders?date=&status=`,
`GET /orders/{id}`, `POST /orders/{id}/{confirm|cancel|deliver|mark-paid}`,
`GET /production-run?date=YYYY-MM-DD`.

Owner, price tiers (Beyond v1, not yet in scope; see the build plan in
county-architecture): `GET/POST /price-tiers`, `PUT /price-tiers/{id}`,
`GET /price-tiers/{id}/prices`, `PUT/DELETE /products/{id}/tier-prices/{tierId}`,
`PUT /resellers/{id}/price-tier`. A reseller on a tier sees and is charged the
tier price where one is set, else the product's base price.

Owner, quotes (Beyond v1; build plan §3 in county-architecture):
`GET /quotes?status=&resellerId=`, `POST /quotes`, `GET /quotes/{id}`,
`POST /quotes/{id}/{send|cancel}`. A quote prices its lines for one reseller as an order
placed now would be (tier price, else base price) and captures them. Only a sent quote
shows on the reseller's link.

Owner, short-delivery reports (Beyond v1; build plan §4 in county-architecture, ADR 0012):
`POST /orders/{id}/discrepancy` (201; 200 when the same key is replayed), `GET /orders/{id}/discrepancy`,
`POST /orders/{id}/discrepancy/{approve|decline}`, `GET /discrepancies?status=open|approved|declined`.

Public reseller: `GET /order/{token}`, `POST /order/{token}/orders`,
`GET /order/{token}/orders/{id}`, `POST /order/{token}/orders/{id}/cancel`,
`GET /order/{token}/quotes` (open quotes), `GET /order/{token}/quotes/{id}`,
`POST /order/{token}/quotes/{id}/accept` (body `{idempotencyKey}`; 201 with the new order,
200 with the order an accepted quote already placed), `POST /order/{token}/quotes/{id}/decline`.

Reseller, short-delivery reports: `POST /order/{token}/orders/{id}/discrepancy` (201; 200 replay),
`GET /order/{token}/orders/{id}/discrepancy`, `POST /order/{token}/orders/{id}/discrepancy/{approve|decline}`.

Accepting a quote places an order at the quote's captured prices, even if a product's
price has changed or it has been deactivated since; placing an order directly uses today's
prices and refuses inactive products. The order gets the delivery date of an order placed
at acceptance, not the estimate shown on the quote. Quotes expire on read: a sent quote
reads as `expired` the day after its `expiresOn`, and can no longer be accepted or
declined. Quote numbers are a per-business sequence separate from order numbers.

Reseller links (W2, ADR 0018): a link works for 90 days after its token was issued. Creating a reseller and
`POST /resellers/{id}/reissue-link` issue a token and set `token_expires_at` 90 days ahead; the old token is
unknown at once. `ResellerView.linkExpiresAt` shows the expiry. Using a link, unblocking and editing a
reseller never change it, and no request body can set it (extra fields are ignored). The lifetime is the
constant `ResellerLink.LIFETIME`, not a setting. An expired link is refused at its expiry, not after it, and
exactly as a blocked, unknown or malformed one is: the same `404` with the same body and content type on every
`/order/{token}/...` operation, so nothing tells a caller which it was. `persistence/ResellerLinks.resolve` is
the single lookup (it also issues tokens) and every reseller service goes through it; the link is resolved
before an idempotency key is looked up, so a replay through an expired link is a 404 too. Reissuing a blocked
reseller's link leaves them blocked, and unblocking does not renew an expired link. V9 gives existing links 90
days from the migration. The owner's reseller operations are one method each in `OwnerResellerResource`
(`GET/POST /resellers`, `PUT /resellers/{id}`, `POST /resellers/{id}/block`, `/unblock`, `/reissue-link`).

Order history (W3): every successful order action (confirm, cancel by the owner, cancel by the
reseller, deliver, mark paid) writes one `order_event` row in the transaction that changes the
order, after the rule check, so a rejected action leaves nothing. An event has the time, the
status before and after, the order's `paid` flag afterwards, and the actor: the owner's user id
or the reseller's id, taken only from the verified subject or the link's token, never from the
request. Delivering as paid is one event; marking paid later is an event from `delivered` to
`delivered`. `OrderView.events` lists them oldest first on `GET /orders/{id}`, on the owner
action responses and on the reseller's order, cancel, placement-replay and quote-acceptance
replay responses; it is empty for an order that has had no action, and for orders changed
before V6, which are not backfilled. Each event carries `actorName` and no id: the owner sees
the owner's display name (which may be null) or the reseller's name; a link holder sees the
reseller's own name and the business name for what the owner did, never the owner's name or
email. A database trigger rejects `UPDATE` and `DELETE` of an event (`TRUNCATE` is not
covered). Placement has no event: `placedAt` records it.

Fractional quantities (W1, ADR 0017): a line's `quantity` is a decimal above 0 and at most 9999 with up
to three decimal places, on orders and quotes alike; a fourth decimal, zero, a negative, a non-number
and an out-of-range value are all 422 keyed `lines[i].quantity`, as is a huge exponent such as `1e400`
(one too large for the parser to hold is a 400). Only a `kg` product takes a fraction; a fraction of a
`pack` or `pc` is a 422 on the same key. A line total is the unit price times the quantity, rounded half
up to centavos, and must come to at least 0.01 and fit `numeric(12,2)`; the order total is the sum of the
rounded lines, so the lines shown always add up. Quantities leave the API without trailing zeros (`10`,
`1.5`). An accepted quote copies its lines as captured, never re-rounded. The database holds the quantity
as `numeric(7,3)` with a `quantity > 0 AND quantity <= 9999` check on `order_line` and `quote_line`.
`Pricing` prices submitted lines for both orders and quotes under the existing lock order. Deploy V7 with
the release that reads decimals: the previous release would read a fractional row truncated (1.5 kg as
1), so do not serve it beside this one once fractional orders exist.

Short-delivery reports (ADR 0012): a delivered order takes one report, filed by its reseller or by the
owner, listing only the products whose received quantity differs from what was ordered (`lines`, 1 to
50, each `{productId, receivedQuantity}`; products left out arrived as ordered), an optional `note` of
at most 500 characters and an `idempotencyKey`. The report is a separate record: the order's lines,
total, status and `paid` flag never change, and nothing is credited or refunded. It copies each line's
name, unit, ordered quantity and price from the order line, so what the request says about those, about
the shortfall, the filer or the status is ignored. `receivedQuantity` follows the quantity rule above
(0 to 9999, up to three decimals, a fraction only of a `kg` product; 0 means nothing arrived) and may
exceed the ordered quantity: over-delivery is accepted and gives a negative shortfall. A line equal to
what was ordered is a 422. A line's shortfall is `(ordered - received) x price` rounded half up to
centavos with the sign kept, the report's is the sum, and both must fit `numeric(12,2)` (422 on
`lines[i].receivedQuantity`, or on `lines` for the sum); the figure is information only. A product not on
the order is `lines[i].productId` "This product is not on the order", whether or not it exists. Filing
on an order that is not delivered is 409 "Order is now <status>"; a second report on an order is 409
"This order already has a report". A key replays (200, the same report) only for the same filer on the
same order; the key of another order or of the other party is 409 "Idempotency key already used" with
no report data. The other party resolves an open report once, as `approved` or `declined`, with an
optional `note` of at most 500 characters (the body may be empty or `{}` but is still sent as JSON, like
every JSON body here): the filer resolving their own report is 409 "Only the other party can resolve
this report", and a resolved report is 409 "This report is now <status>". A report is read as 404 when
there is none; `filedBy` and `resolvedBy` are `owner` or `reseller`, never an id or a name. The owner
list, newest first, gives each report's order number and reseller name; an unknown or empty `status`
filter is a 422 on `status`. A database trigger rejects `UPDATE` and `DELETE` of a line and `DELETE` of
a report, lets an open report change only by being resolved (what was filed stays as it was), and lets a
resolved one change no more (`TRUNCATE` is not covered).

Date filters are delivery dates. Money is decimal PHP, quantity up to three decimals. Status
strings are lowercase. The order list includes each order's `resellerName` and its
`items` (product name, unit, quantity) so a dashboard row needs no further calls;
order detail includes `resellerName` too. The settings preview takes unsaved values,
needs a signed-in owner but no business, and returns the delivery date an order placed
now would get.

Every error is `application/problem+json` with `type`, `title`, `status`, `detail` and
`errors`, a map from a field's JSON path (`price`, `lines[0].quantity`) to a message;
it is empty when no single field is at fault. A body that is not a JSON object is 400.
A field of the wrong JSON type and a failed validation are both 422, keyed the same
way. A missing body is reported as `body`; query parameters by their name. The 401
from Google token verification comes from Quarkus OIDC and has no body. Mutation payload shapes are in `Inputs.java` and OpenAPI.
Do not log bearer tokens/order links. API responses are `no-store` with no referrers.

## Implementation notes / limits

- Persistence is Hibernate ORM with Panache entities (`persistence/`), per ADR 0013
  in county-architecture. Each service method is one `@Transactional` unit. Tenant
  numbering, order idempotency and state changes use JPA pessimistic locks, always in
  this order: reseller, business, products by ascending id, order. Keep that order in
  new workflows. A product's tier prices are guarded by that product's lock: take
  it before reading or writing one. Flyway owns the schema; Hibernate never generates
  or alters it.
- Added lock targets (ADR 0015 in county-architecture): a row outside those four is
  locked only after every one of the four the workflow takes, and added targets are
  taken in this order. Add a line here in the PR that adds a target.
  1. `price_tier`: assigning a reseller's tier share-locks it after the reseller, so a
     concurrent deactivation commits first.
  2. `quote`: accepting locks it after the reseller and business (it locks no product,
     since the order copies the quote's captured prices); the order it inserts is not a
     lock target. Send, cancel and decline lock only the quote. Creating a quote takes no
     quote lock: it share-locks the reseller, locks the business for the quote number,
     then share-locks products by ascending id.

  `discrepancy_report` and its lines are not a lock target: filing and resolving lock the order for
  writing (the reseller first, on a reseller's request) and nothing locks a report on its own; the
  report's key is checked and its row written under that order lock, and `UNIQUE (tenant_id, order_id)`
  backs it up. Lines copy from the order's lines, so no product is locked.

  `order_event` is not a lock target: an action inserts its event after it has locked the
  order (and the reseller, for a reseller's cancel) and never locks an event. The row's foreign
  keys take only key-share locks on the order, the reseller and the owner's `app_user` row, which
  those updates do not conflict with. The owner's user id is read from the business without a
  lock.
- First sign-in takes a PostgreSQL advisory lock through a native query on the same
  entity manager, so concurrent first calls create one user.
- Reseller token is stored as a bearer secret so an owner can copy its current link, and it
  expires 90 days after issue (ADR 0018). Protect database backups and keep URL credentials out
  of logs and analytics.
- Verified Google subjects resolve through `external_identity(provider, subject)`
  to `app_user`; business ownership uses the user ID. First `/me` provisions the
  user idempotently. Profile fields come only from verified token claims; email is
  never an account-linking key. V2 backfills V1 owners without changing business IDs.
- Public HTTP responses use explicit typed DTOs rather than exposing database maps.
- No web UI, real Google browser acceptance, production deployment or operational
  backup validation is included.
- Rate limiting is not in the API. The deployment spec (sections 5 and 11) puts it at
  the edge: a Cloudflare rule on `/api/order/*`, for example 60 requests a minute per
  IP. Token probing is already blind, since unknown, blocked and expired tokens are all the same 404.
- Local defaults are development credentials only. Set explicit real credentials and
  Google audience before any production use; deployment is a separate authorized task.

### Explicit next backend work

This is a draft foundation, not a complete v1 backend certification:
- Extend concurrency coverage when adding new workflows. Existing tests cover
  same-key replays, distinct-key numbering, and product edit/deactivation interleavings.

Do not treat this branch as production-ready or deploy it before these integration
and contract gaps are addressed and reviewed.
