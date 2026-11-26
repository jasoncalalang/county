# county

Monorepo: `api/` (Quarkus, Java 21, Gradle) and `web/` (Next.js, App Router,
TypeScript). Read `AGENTS.md` first. Specs are in `docs/superpowers/specs/`;
decision records are in the `county-architecture` repository. The ordered work
queue, its decision register and the fresh-machine bootstrap are in
`docs/agent-playbook.md`.

## Checks

- api: `cd api && COUNTY_TEST_SCHEMA=county_test_$(date +%s) ./gradlew build`
  (needs Postgres and `COUNTY_TEST_JDBC_URL`, see `api/README.md`). Use a new
  schema for each run; the tests never clean one.
- web: `cd web && npm ci && npm run lint && npm test && npm run build`
- web end to end: `cd web && npm run e2e -- --trace off`

## Cloud sessions

Use the `county` cloud environment. It provides Postgres 16 with a `county`
role and the `county` and `county_test` databases, sets
`COUNTY_TEST_JDBC_URL`, and installs Helm. CI runs Postgres 17; the suite
passes on both.

Postgres is not running when a session starts or resumes. Start it before
API tests or dev mode:

```bash
pg_ctlcluster 16 main start
```
