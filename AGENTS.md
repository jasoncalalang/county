# County engineering

Canonical specifications live in `jasoncalalang/county-architecture`, working baseline
`2d00e76319dbf9efd7832a4a36428000987ef39c`. The copies under `docs/superpowers/specs`
may predate architecture amendments; do not silently revert those amendments.

Continuation work queue: [`docs/agent-playbook.md`](docs/agent-playbook.md) — ordered,
testable work items derived from an analysis of the production Itemcount client. It
defers to the canonical specifications; read it before starting backend or web work.

Backend lives in `api/`: Java 21, Gradle, Quarkus, real PostgreSQL, Flyway.
Read `api/README.md` before running tests. No Docker is required, but the suite needs a
reachable PostgreSQL: nothing starts it for you, so a fresh cloud session runs
`pg_ctlcluster 16 main start` first and gives each test run a new schema. Never point
test credentials at production.

Web lives in `web/`: Next.js 16, App Router. Read `web/AGENTS.md` before touching it —
this Next.js differs from older App Router conventions, and its guidance block must not
be reverted.

Tenant scope comes from the verified identity, never from request fields: a Google ID
token's subject resolves through `external_identity(provider, subject)` to an `app_user`,
and a reseller token resolves to its tenant. Never link accounts by email. Every endpoint
change needs isolation/validation tests and a committed OpenAPI diff
(`./gradlew updateOpenApi`; `verifyOpenApi` runs in `build`). Write tests first, as red-green-refactor commits; the engineering rules and the
plan-build-review loop are in [`CLAUDE.md`](CLAUDE.md). Do not merge or deploy without
explicit authorization.
