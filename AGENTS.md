# County engineering

Canonical specifications live in `jasoncalalang/county-architecture`, working baseline
`2d00e76319dbf9efd7832a4a36428000987ef39c`. The copies under `docs/superpowers/specs`
may predate architecture amendments; do not silently revert those amendments.

Backend lives in `api/`: Java 21, Gradle, Quarkus, real PostgreSQL, Flyway.
Read `api/README.md` before running tests. No Docker is required. Never point test
credentials at production. Tenant scope comes from the Google subject or reseller
token, never request fields. Every endpoint change needs isolation/validation tests.
Write tests first. Do not merge or deploy without explicit authorization.
