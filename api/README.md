# county api

Quarkus on Java 21, built with Gradle. The REST API is served under `/api`;
health and OpenAPI stay under `/q`.

## A Postgres is required, Docker is not

Tests and dev mode use a Postgres you already have
([ADR 0005](https://github.com/jasoncalalang/county-architecture/blob/main/adr/0005-tests-run-without-docker.md)).
Dev Services are disabled, so no container runtime is needed.

One-time setup, as a Postgres superuser:

```sql
CREATE ROLE county LOGIN CREATEDB PASSWORD 'county';
CREATE DATABASE county OWNER county;       -- dev mode
CREATE DATABASE county_test OWNER county;  -- tests
```

## Build and test

```bash
export COUNTY_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/county_test
./gradlew build
```

Flyway cleans and rebuilds the schema of that database on every test run, so
never point `COUNTY_TEST_JDBC_URL` at data you want to keep.
`COUNTY_TEST_DB_USER` and `COUNTY_TEST_DB_PASSWORD` override the default
`county` / `county`.

## Dev mode

```bash
./gradlew quarkusDev
```

Dev mode connects to `jdbc:postgresql://localhost:5432/county` as `county`;
`COUNTY_DEV_JDBC_URL`, `COUNTY_DEV_DB_USER` and `COUNTY_DEV_DB_PASSWORD`
override that. Readiness is at <http://localhost:8080/q/health/ready> and the
OpenAPI document at <http://localhost:8080/q/openapi>.
