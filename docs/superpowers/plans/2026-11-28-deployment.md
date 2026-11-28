# Plan: deployment artifacts (images, Helm chart, pipeline)

Date: 2026-11-28. Work item: roadmap item 5. Spec:
`docs/superpowers/specs/2026-10-31-county-v1-kubernetes-deployment.md` (status: proposed).
Decisions: ADR 0006 (Jenkins deploys with Helm on `main`), ADR 0007 (Kaniko images tagged by
commit SHA), ADR 0008 (Postgres StatefulSet on Longhorn), ADR 0009 (Flyway at API start),
ADR 0010 (one hostname, API under `/api`, Auth.js under `/auth`), ADR 0011 (Secrets created
outside the chart). No migration. No OpenAPI change.

**Nothing in this item deploys.** `AGENTS.md`: no deploy without explicit authorization. The
item writes and verifies the artifacts; the first install is Jason's, from `deploy/README.md`.

## 1. Scope and non-goals

In scope:

- `api/Dockerfile` and `web/Dockerfile`, build context at the repository root (spec §8), plus a
  root `.dockerignore`. Multi-stage: the image builds from source, so Kaniko needs nothing but
  the checkout. Both run as a non-root numeric uid and work with a read-only root filesystem
  (api: writable `/tmp`; web: writable `/app/.next/cache`).
- Helm chart `deploy/county` as laid out in spec §9: api and web Deployments, Services,
  ConfigMaps, NetworkPolicies; postgres StatefulSet, headless Service, NetworkPolicy; Ingress.
  `deploy/values/demo.yaml` with the placeholder hostname `county.example.ph`.
- `Jenkinsfile` at the root with the stages of spec §8.
- `deploy/README.md`: first install, rollback, restore, secret rotation, the Cloudflare rate
  limit rule on `/api/order/*` (where rate limiting lives), and what is unverified (below).
- `.github/workflows/deploy.yml` ("Deploy verification"): chart tests, `helm lint`, and a build
  of both images followed by a container smoke test. It never pushes an image and never
  touches a cluster. `web.yml` and `backend.yml` are not changed.
- Playbook: status board and bootstrap notes updated.

Non-goals (YAGNI, or not decided):

- No deploy, no cluster access, no image push, no registry credentials anywhere.
- No Micrometer, no `/q/metrics`, no ServiceMonitor template, no JSON log extension. Each is a
  new runtime dependency for something the cluster cannot consume yet (spec A6: no
  Prometheus). Listed for Jason in the PR.
- The API access log stays off. The spec (§12) asks for it on, but request paths contain order
  link tokens and `CLAUDE.md` forbids logging them. Listed for Jason in the PR.
- No Longhorn recurring-job manifest (cluster-level object outside the namespace; the README
  gives the label command), no `pg_dump` CronJob, no HPA, PDB, second environment, GitOps,
  sealed secrets, native image (spec §13).
- No `postgres.enabled=false` / `externalHost` switch and no `imagePullSecrets` value until an
  environment needs them.
- No application code change except what an image needs to run (expected: none).

Deviation from the spec's variable tables, on purpose: the chart sets the variables the code
reads today (`COUNTY_JDBC_URL`, `COUNTY_DB_USER`, `COUNTY_DB_PASSWORD`, `GOOGLE_CLIENT_ID`,
`COUNTY_PUBLIC_URL` for api; `COUNTY_API_URL`, `AUTH_URL`, `AUTH_TRUST_HOST`, `AUTH_SECRET`,
`AUTH_GOOGLE_ID`, `AUTH_GOOGLE_SECRET`, `PORT`, `HOSTNAME=0.0.0.0` for web), not the
`QUARKUS_*` and `API_INTERNAL_URL` names in spec §6. The builder confirms each name against
`application.properties`, `web/auth.ts`, `web/lib` and `web/.env.example` before using it.

## 2. Threat model

Assets: the three Secrets (Google client, Postgres password, Auth.js secret); the database
volume; the cluster namespace; the images and their tags; reseller link tokens in transit.

Actors: owner; reseller holding a link; anonymous caller on the internet; owner of another
tenant (no new surface here); plus, for this item, a compromised api or web container, another
workload in the cluster, and anyone who can read the repository, CI logs or Helm history.

Entry points: the Ingress (`/` and `/api`); pod-to-pod traffic; the image build; the pipeline
and its credentials; values files.

| # | Abuse case | Control | Test |
|---|---|---|---|
| E1 | A secret value ends up in git, values, a ConfigMap or Helm release history | The chart renders no `Secret`; every secret reaches a pod by `secretKeyRef` to a named existing Secret; ConfigMaps hold no secret key | C1 |
| E2 | Health, OpenAPI or future metrics endpoints exposed to the internet | Ingress routes only `/api` and `/`; `/q` is never a path | C2 |
| E3 | A compromised container writes to its filesystem, runs as root or gains privileges | `runAsNonRoot`, numeric uid, `readOnlyRootFilesystem`, `allowPrivilegeEscalation: false`, drop `ALL`, `seccompProfile: RuntimeDefault` on api and web; images declare a numeric non-root `USER` | C3, I1, I2 |
| E4 | Another workload, or web, connects to Postgres | NetworkPolicy: postgres accepts 5432 from api pods only | C4 |
| E5 | Another workload calls api or web directly, bypassing the edge | NetworkPolicy: api accepts 8080 from web and the ingress namespace; web accepts 3000 from the ingress namespace | C4 |
| E6 | A mutable tag (`latest`) ships unreviewed code or hides what is running | `image.tag` is `required` with no default; it must be a 40-character lowercase hex SHA or the render fails; postgres image is pinned in values | C5 |
| E7 | A render without the public URL builds order links to the wrong host | `publicUrl` is `required` and must start with `https://` | C5 |
| E8 | A failed migration or bad release takes the site down | Rolling update `maxSurge 1`, `maxUnavailable 0`; startup, liveness and readiness probes per spec §4; pipeline deploys with `--atomic --wait` | C6, P1 |
| E9 | Web goes unready when api is down, taking the whole site down | Web probes hit `/healthz` only, which does not call the API | C6, I2 |
| E10 | A branch build deploys | The deploy and smoke stages run only on `main` | P1 |
| E11 | The pipeline leaks the registry token or has cluster-wide rights | The `Jenkinsfile` holds no credential value and never echoes one; the token is a mounted file; README documents a namespace-only Role for `jenkins-deployer` | P1 (no literal secret; `set +x` not needed because nothing prints it), review |
| E12 | A dependency or base image changes under a pinned tag | Base images pinned by tag and digest; GitHub Actions pinned by SHA; workflow `permissions: contents: read`; `npm ci` and the Gradle wrapper inside the image builds | P2, review |
| E13 | The image carries secrets or junk from the working tree | `.dockerignore` excludes `.git`, `**/.env*` (except `.env.example`), `node_modules`, `build`, `.next`, test results; the web image holds no environment-specific value and no `NEXT_PUBLIC_` secret | I3 |
| E14 | Reseller link tokens appear in logs | Access log stays off in the chart's configuration; no template sets it on | C7 |
| E15 | Rate limiting is assumed to exist in the API | README states it is a Cloudflare rule on `/api/order/*` and gives the rule; nothing in the chart or API adds one | review |

## 3. Design

### Images

- `api/Dockerfile`: stage 1 on a pinned Temurin 21 JDK image runs
  `./gradlew quarkusBuild -x test` in `api/`; stage 2 on a pinned
  `ubi9/openjdk-21-runtime` copies `build/quarkus-app` and runs it as uid 185 (ADR 0007).
- `web/Dockerfile`: stage 1 on a pinned Node 22 image runs `npm ci` and `npm run build`
  (no `COUNTY_DEV_API_ORIGIN`, so no rewrite is baked in); stage 2 copies
  `.next/standalone`, `.next/static` and `public` (if it exists) and runs `node server.js` as
  a numeric non-root uid.
- If a base image digest cannot be resolved from this environment, pin the tag only
  and say so in the report instead of inventing a digest.

### Chart

Layout as spec §9 without `servicemonitor.yaml`. Labels as spec §3. Values: `image.registry`,
`image.tag` (required), `publicUrl` (required), `ingress.enabled`, `ingress.className`,
`ingress.host` derived from `publicUrl`, `api.replicas`, `api.resources`, `web.replicas`,
`web.resources`, `postgres.image`, `postgres.storageClass`, `postgres.size`,
`secrets.google|postgres|web`, `networkPolicy.enabled`, `networkPolicy.ingressNamespace`.
ConfigMap checksum annotation on each pod template. Resources, probes, ports and writable
paths per spec §4.

### Chart tests

`deploy/tests/test_chart.py`: Python `unittest`, runs `helm template` with
`deploy/values/demo.yaml` and a 40-character test SHA, parses the YAML with PyYAML and asserts
on the objects. Run as `python3 -m unittest discover deploy/tests`. This is the test seam for
the chart; no Helm plugin is added.

### Pipeline

`Jenkinsfile`: Kubernetes agent pod (`jdk`, `node`, `postgres`, `kaniko`, `helm`, images
pinned), stages Checkout, api, web, images (parallel Kaniko, `--destination
…:$GIT_COMMIT`, cache repo), chart (`helm lint`, `helm template`, chart tests), deploy and
smoke with `when { branch 'main' }`. It cannot be executed here; `deploy/tests/test_pipeline.py`
checks its text for the properties in P1, and the README says the first Jenkins run is the
real test.

`.github/workflows/deploy.yml`: on pull requests and pushes to `main` touching `deploy/**`,
`api/**`, `web/**`, the Dockerfiles, `Jenkinsfile` or itself. Job `chart`: install a pinned
Helm with its checksum verified, `helm lint`, chart and pipeline tests. Job `images`: `docker
build` both images, then run `deploy/tests/smoke.sh`.

`deploy/tests/smoke.sh`: starts Postgres, then the api image with `--read-only --tmpfs /tmp
--cap-drop ALL --security-opt no-new-privileges` and waits for `/q/health/ready` to be 200;
starts the web image with `--read-only --tmpfs /app/.next/cache` and the same flags and
expects `/healthz` 200 and `/` to answer; checks `docker inspect` shows a numeric non-root
user for both; cleans up. Test-only credentials are generated in the script, never committed
values that look real.

## 4. Test list

Helm is not installed in the current cloud session: install the pinned release binary into a
scratch directory (verify its published checksum) before cycle 1. If Docker has no daemon
here, I1–I3 run only in CI: say so in the commit bodies, and the PR waits for that job.

| # | Behaviour | Where | Fails first because |
|---|---|---|---|
| C0 | The chart lints and renders with the demo values | `test_chart.py` | no chart |
| C1 | No `Secret` object is rendered; api and web take each secret variable by `secretKeyRef` to the Secret named in values; no ConfigMap key matches password, secret or token | same | no templates |
| C2 | The Ingress has exactly two paths, `/api` to `county-api:8080` and `/` to `county-web:3000`, no TLS section, no `/q` | same | no template |
| C3 | api and web containers carry the pod security settings of spec §4; postgres runs as uid 999 with `fsGroup` 999; writable paths are the emptyDirs and the PVC | same | no templates |
| C4 | NetworkPolicies: postgres ingress only from api on 5432; api from web and the ingress namespace on 8080; web from the ingress namespace on 3000; `networkPolicy.enabled=false` renders none | same | no templates |
| C5 | Rendering fails without `image.tag`, with a tag that is not a 40-character hex SHA (`latest`), without `publicUrl`, and with an `http://` `publicUrl`; images are `<registry>/county-api:<sha>` and `county-web:<sha>` | same | renders anyway |
| C6 | Probes, ports, resources and rolling-update settings per spec §4; web probes are `/healthz` only | same | no templates |
| C7 | The api ConfigMap carries the variable names the code reads and does not turn the access log on; the datasource URL points at the postgres Service; `COUNTY_PUBLIC_URL` equals `publicUrl`; a changed ConfigMap value changes the pod template checksum | same | no templates |
| C8 | Labels of spec §3 on every object; selectors match pod labels; StatefulSet has one 10Gi `longhorn` claim | same | no templates |
| I1 | api image: non-root numeric user; ready on `/q/health/ready` with a read-only root filesystem and migrations applied | `smoke.sh` | no Dockerfile |
| I2 | web image: non-root numeric user; `/healthz` 200 with a read-only root filesystem and no API running | `smoke.sh` | no Dockerfile |
| I3 | `.dockerignore` keeps `.git`, env files, `node_modules` and build output out of the context | `test_pipeline.py` | no file |
| P1 | `Jenkinsfile`: deploy and smoke stages are guarded by `branch 'main'`; the deploy command has `--atomic --wait` and `image.tag=$GIT_COMMIT`; image destinations use `$GIT_COMMIT` and never `latest`; no literal credential | `test_pipeline.py` | no file |
| P2 | `deploy.yml`: `permissions: contents: read`; every `uses:` is pinned to a 40-character SHA; no `docker push`, `helm upgrade` or `kubectl` | `test_pipeline.py` | no file |

## 5. Commit sequence

1. `docs: plan deployment artifacts` (this file, alone).
2. Cycle 1, chart skeleton and guards: `test:` C0, C5 → `feat:` → `refactor:` or reason.
3. Cycle 2, workloads: `test:` C1, C3, C6, C7, C8 → `feat:` → `refactor:`.
4. Cycle 3, network: `test:` C2, C4 → `feat:` → `refactor:` or reason.
5. Cycle 4, images: `test:` I1–I3 (`smoke.sh`, failing for want of Dockerfiles) → `feat:`
   Dockerfiles and `.dockerignore` → `refactor:` or reason.
6. Cycle 5, pipeline: `test:` P1, P2 → `feat:` `Jenkinsfile`, `deploy.yml` → `refactor:` or
   reason.
7. `docs:` `deploy/README.md`, playbook, root README pointer.

## 6. Open questions

None that block the build. For Jason in the PR: (1) nothing was deployed and the `Jenkinsfile`
has never run; (2) metrics, JSON logs and the access log are left out or off, with reasons;
(3) the chart uses the variable names the code reads, not the spec's table; (4) the hostname
in `deploy/values/demo.yaml` is the placeholder; (5) cluster assumptions A1–A6 are unconfirmed.
