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

## How work is built

Every work item runs this loop. The agents are in `.claude/agents/`.

1. **Plan** (`planner`, Fable): writes `docs/superpowers/plans/<date>-<item>.md`
   with scope and non-goals, a threat model, the ordered test list, the commit
   sequence, migrations and OpenAPI changes. No code.
2. **Build** (`builder`, Sonnet, high effort): works the test list in
   red-green-refactor commits and runs the checks above.
3. **Review** (`reviewer`, Opus): reviews the branch against the plan and the
   rules below. Verdict `CLEAN` or `CHANGES` with findings.
4. **Loop** steps 2 and 3 until the reviewer says `CLEAN`. After 5 rounds
   without `CLEAN`, stop and ask Jason.
5. **Final review** (`final-reviewer`, Fable): the whole PR against the plan,
   the spec and its ADRs, and the commit history. Only Jason merges.

## Engineering rules

These apply to every change, with or without the agents. A reviewer treats a
broken rule as a finding.

### TDD, visible in history

A feature or fix is at least three commits, in this order, and the PR is
merged without squashing:

1. `test: <behaviour>` adds only tests, and they fail for the expected reason
   (an assertion, not a compile error). In Java, add the smallest signature
   that compiles and throws `UnsupportedOperationException`. Put the failing
   test names and the failure in the commit body.
2. `feat: <behaviour>` or `fix: <bug>` adds the least code that passes. Tests
   do not change in this commit.
3. `refactor: <what>` removes the duplication and size the green step left.
   Behaviour and tests do not change. Skip it only if the body of the green
   commit says there is nothing to clean up.

- One behaviour per cycle; a work item is several cycles.
- A bug gets a reproducing test before its fix.
- Never skip, disable or weaken a test to get green.
- Code that reads time takes a `java.time.Clock` (web: a `now` argument), so
  tests run at fixed times, including the 16:00 Manila cut-off.
- Web logic that can be tested (validation, mapping, fetch and retry) lives in
  `web/lib/` with unit tests. Pages and route files only wire and render.

### SOLID

- **Single responsibility.** One resource class per area (identity and
  settings, catalog, resellers, price tiers, orders, quotes). A service method
  does one job; queries, rules, locking and response mapping are separate.
  `OwnerResource` and `Owners` are already too big: do not add to them, split
  the part you touch.
- **Open/closed.** Statuses and actions are enums (`OrderRules.Status`), never
  raw strings. One explicit method per action; no methods that branch on a
  string, a null id or which argument is null.
- **Liskov.** Prefer composition; no inheritance beyond Panache entities.
- **Interface segregation.** Inject only the services a class uses.
- **Dependency inversion.** Packages depend downward only:
  `api` → services → `domain`. Services return domain results and throw domain
  exceptions; only `api` maps them to `Views` and `Problem`. Collaborators and
  the clock are injected, not called through statics.

### YAGNI

- Build what the current work item and its accepted ADRs ask for, nothing
  more. No feature flags, unused config, speculative parameters or "for later"
  endpoints.
- No interface with one implementation and no base class, unless it is a test
  seam at a real boundary (HTTP, clock, external service).
- Abstract on the third repetition, in a `refactor:` commit.
- No new runtime dependency unless the PR says why the platform or existing
  code cannot do it.

### Security by design

Each plan has a threat model: the assets touched, the actors (owner, reseller
holding a link, anonymous caller, owner of another tenant), the entry points,
and the abuse cases. Every abuse case becomes a test in the test list.

- **Tenant isolation.** The tenant comes from the verified credential, never
  from the request. Cross-tenant access is `404`. Every endpoint change adds an
  `IsolationApiTest`-style case.
- **Authentication and least privilege.** Deny by default: every new endpoint
  states who may call it and has a test for the caller who may not. Unknown,
  blocked and expired reseller tokens all return the same `404`.
- **Input validation.** Validate at the boundary with typed inputs and explicit
  bounds (lengths, ranges, the money ceiling in `domain/Money`). Reject what
  does not validate with the Problem Details `errors` map. Every validated
  field gets a rejection test.
- **Injection and output.** SQL is parameterised only; no string-built
  queries. The reseller order page builds HTML only with the escaping `html`
  template in `web/lib/order-page/html.ts`; `raw` is for constants, never for
  data.
- **Concurrency and replay.** Follow the lock order of ADR 0013 and ADR 0015.
  Mutations a client may retry take an idempotency key.
- **Secrets and personal data.** No secret in the repository, a test fixture
  or a `NEXT_PUBLIC_` variable; deployment secrets follow ADR 0011. Never log
  bearer tokens, order links or mobile numbers. API responses stay `no-store`
  with no referrer.
- **Dependencies.** Pin exact versions, commit lockfiles, check new or bumped
  packages for advisories, and pin GitHub Actions. Workflows keep
  `permissions: contents: read` unless a job needs more.
- **Errors.** No stack traces, SQL or internal ids of other tenants in a
  response.
- **Rate limiting** stays at the Cloudflare edge. Do not add it to the API.

A change that weakens any of these needs an ADR in `county-architecture`
first.

`.claude/settings.json` denies agents reading local env and key files and
force-pushing. `web/.env.example` stays readable and holds no real values.
