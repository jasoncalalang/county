# county

Monorepo: `api/` (Quarkus, Java 21, Gradle) and `web/` (Next.js, App Router,
TypeScript). Specs are in `docs/superpowers/specs/`; decision records are in
the `county-architecture` repository.

## Checks

- api: `cd api && ./gradlew build` (needs Postgres and `COUNTY_TEST_JDBC_URL`, see `api/README.md`)
- web: `cd web && npm ci && npm run lint && npm test && npm run build`
- web end to end: `cd web && npm run e2e -- --trace off`

## Cloud sessions

Use the `county` cloud environment. It provides Postgres 16 with a `county`
role and the `county` and `county_test` databases, sets
`COUNTY_TEST_JDBC_URL`, and installs Helm.

Postgres is not running when a session starts or resumes. Start it before
API tests or dev mode:

```bash
pg_ctlcluster 16 main start
```
