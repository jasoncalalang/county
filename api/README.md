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

Owner: `/me`, `POST /tenants`, `GET/PUT /settings`, `GET/POST /products`,
`PUT /products/{id}`, `GET/POST /resellers`, `PUT /resellers/{id}`,
`POST /resellers/{id}/{block|unblock|reissue-link}`, `GET /orders?date=&status=`,
`GET /orders/{id}`, `POST /orders/{id}/{confirm|cancel|deliver|mark-paid}`,
`GET /production-run?date=YYYY-MM-DD`.

Public reseller: `GET /order/{token}`, `POST /order/{token}/orders`,
`GET /order/{token}/orders/{id}`, `POST /order/{token}/orders/{id}/cancel`.

Date filters are delivery dates. Money is decimal PHP, quantity whole units. Status
strings are lowercase. Mutation payload shapes are in `Inputs.java` and OpenAPI.
Do not log bearer tokens/order links. API responses are `no-store` with no referrers.

## Implementation notes / limits

- Explicit parameterized JDBC transactions through Agroal protect tenant numbering,
  order idempotency and state changes. This slice uses SQL rather than Panache entity
  methods for these lock-sensitive operations; proposed [ADR 0013](https://github.com/jasoncalalang/county-architecture/pull/2)
  records this exception for review. Do not mix an independent ORM transaction
  with this connection. Panache remains available for future conventional persistence.
- Reseller token is stored as a bearer secret so an owner can copy its current link.
  Protect database backups and keep URL credentials out of logs and analytics.
- Verified Google subjects resolve through `external_identity(provider, subject)`
  to `app_user`; business ownership uses the user ID. First `/me` provisions the
  user idempotently. Profile fields come only from verified token claims; email is
  never an account-linking key. V2 backfills V1 owners without changing business IDs.
- Public HTTP responses use explicit typed DTOs rather than exposing database maps.
- No web UI, real Google browser acceptance, production deployment, rate limiting,
  or operational backup validation is included.
- Local defaults are development credentials only. Set explicit real credentials and
  Google audience before any production use; deployment is a separate authorized task.

### Explicit next backend work

This is a draft foundation, not a complete v1 backend certification:
- Add settings preview metadata, order-list product/reseller display summaries and
  consistent API field-error shapes for all JSON decoding failures.
- Extend concurrency coverage when adding new workflows. Existing tests cover
  same-key replays, distinct-key numbering, and product edit/deactivation interleavings.

Do not treat this branch as production-ready or deploy it before these integration
and contract gaps are addressed and reviewed.
