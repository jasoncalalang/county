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

Public reseller: `GET /order/{token}`, `POST /order/{token}/orders`,
`GET /order/{token}/orders/{id}`, `POST /order/{token}/orders/{id}/cancel`,
`GET /order/{token}/quotes` (open quotes), `GET /order/{token}/quotes/{id}`,
`POST /order/{token}/quotes/{id}/accept` (body `{idempotencyKey}`; 201 with the new order,
200 with the order an accepted quote already placed), `POST /order/{token}/quotes/{id}/decline`.

Accepting a quote places an order at the quote's captured prices, even if a product's
price has changed or it has been deactivated since; placing an order directly uses today's
prices and refuses inactive products. The order gets the delivery date of an order placed
at acceptance, not the estimate shown on the quote. Quotes expire on read: a sent quote
reads as `expired` the day after its `expiresOn`, and can no longer be accepted or
declined. Quote numbers are a per-business sequence separate from order numbers.

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

Date filters are delivery dates. Money is decimal PHP, quantity whole units. Status
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

  `order_event` is not a lock target: an action inserts its event after it has locked the
  order (and the reseller, for a reseller's cancel) and never locks an event. The row's foreign
  keys take only key-share locks on the order, the reseller and the owner's `app_user` row, which
  those updates do not conflict with. The owner's user id is read from the business without a
  lock.
- First sign-in takes a PostgreSQL advisory lock through a native query on the same
  entity manager, so concurrent first calls create one user.
- Reseller token is stored as a bearer secret so an owner can copy its current link.
  Protect database backups and keep URL credentials out of logs and analytics.
- Verified Google subjects resolve through `external_identity(provider, subject)`
  to `app_user`; business ownership uses the user ID. First `/me` provisions the
  user idempotently. Profile fields come only from verified token claims; email is
  never an account-linking key. V2 backfills V1 owners without changing business IDs.
- Public HTTP responses use explicit typed DTOs rather than exposing database maps.
- No web UI, real Google browser acceptance, production deployment or operational
  backup validation is included.
- Rate limiting is not in the API. The deployment spec (sections 5 and 11) puts it at
  the edge: a Cloudflare rule on `/api/order/*`, for example 60 requests a minute per
  IP. Token probing is already blind, since unknown and blocked tokens are both 404.
- Local defaults are development credentials only. Set explicit real credentials and
  Google audience before any production use; deployment is a separate authorized task.

### Explicit next backend work

This is a draft foundation, not a complete v1 backend certification:
- Extend concurrency coverage when adding new workflows. Existing tests cover
  same-key replays, distinct-key numbering, and product edit/deactivation interleavings.

Do not treat this branch as production-ready or deploy it before these integration
and contract gaps are addressed and reviewed.
