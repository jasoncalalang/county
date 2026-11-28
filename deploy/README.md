# Deploying county

The chart, the images and the pipeline for the v1 demo on the xeon cluster. Design:
[`docs/superpowers/specs/2026-10-31-county-v1-kubernetes-deployment.md`](../docs/superpowers/specs/2026-10-31-county-v1-kubernetes-deployment.md);
decisions: ADR 0006 to 0011 in `county-architecture`; plan:
[`docs/superpowers/plans/2026-11-28-deployment.md`](../docs/superpowers/plans/2026-11-28-deployment.md).

**Nothing here has been deployed.** The chart was rendered and linted, never installed. The `Jenkinsfile` has
never run. See [What is unverified](#what-is-unverified) before the first install. `AGENTS.md`: nobody deploys
without Jason's explicit go-ahead.

| Path | What |
|---|---|
| `county/` | Helm chart: api, web, postgres, Ingress, NetworkPolicies |
| `values/demo.yaml` | the demo environment: `publicUrl` only (placeholder host `county.example.ph`) |
| `tests/` | chart tests, pipeline checks and the image smoke test |
| `../api/Dockerfile`, `../web/Dockerfile`, `../.dockerignore` | images, built from the repository root |
| `../Jenkinsfile` | build, test, push images, deploy `main` |
| `../.github/workflows/deploy.yml` | runs the tests above and builds both images on pull requests |

## Checks

```bash
# chart and pipeline tests: needs helm 3 on PATH and PyYAML
python3 -m unittest discover deploy/tests

# builds both images and runs them hardened (needs a Docker daemon, pushes nothing)
deploy/tests/smoke.sh            # or: deploy/tests/smoke.sh web
```

A restricted build network can pass extra flags through `DOCKER_BUILD_OPTS`, for example `--network host`.

## The chart

`image.tag` and `publicUrl` have no default. A render fails unless `image.tag` is a 40-character lowercase hex
commit SHA and `publicUrl` starts with `https://`.

| Value | Default | Note |
|---|---|---|
| `image.registry` | `ghcr.io/jasoncalalang` | images are `<registry>/county-api:<sha>` and `county-web:<sha>` |
| `image.tag` | unset, required | the pipeline passes `$GIT_COMMIT` |
| `publicUrl` | unset, required | order links and `AUTH_URL`; the Ingress host comes from it |
| `ingress.enabled`, `ingress.className` | `true`, `nginx` | A3 |
| `api.replicas`, `api.resources` | 1, spec section 4 | |
| `web.replicas`, `web.resources` | 1, spec section 4 | |
| `postgres.image` | `postgres:17` pinned by digest | |
| `postgres.storageClass`, `postgres.size` | `longhorn`, `10Gi` | A2 |
| `secrets.google`, `secrets.postgres`, `secrets.web` | `county-google`, `county-postgres`, `county-web` | names of Secrets that already exist |
| `networkPolicy.enabled`, `networkPolicy.ingressNamespace` | `true`, `ingress-nginx` | |

Environment variable names follow what the code reads today, not the `QUARKUS_*` and `API_INTERNAL_URL`
names in the spec's tables: api `COUNTY_JDBC_URL`, `COUNTY_DB_USER`, `COUNTY_DB_PASSWORD`, `GOOGLE_CLIENT_ID`,
`COUNTY_PUBLIC_URL`; web `COUNTY_API_URL`, `AUTH_URL`, `AUTH_TRUST_HOST`, `AUTH_SECRET`, `AUTH_GOOGLE_ID`,
`AUTH_GOOGLE_SECRET`, `PORT`, `HOSTNAME`. The root path, the `/q` path and Flyway are already in
`application.properties`.

## First install

Confirm the cluster assumptions A1 to A6 of the spec first. Then, with the three Secrets created outside the
chart (ADR 0011) and an image tag whose images the pipeline has pushed:

```bash
kubectl create namespace county-demo
kubectl -n county-demo create secret generic county-google \
  --from-literal=GOOGLE_CLIENT_ID=... --from-literal=GOOGLE_CLIENT_SECRET=...
kubectl -n county-demo create secret generic county-postgres \
  --from-literal=POSTGRES_PASSWORD="$(openssl rand -base64 32)"
kubectl -n county-demo create secret generic county-web \
  --from-literal=AUTH_SECRET="$(openssl rand -base64 32)"
helm upgrade --install county deploy/county -n county-demo \
  -f deploy/values/demo.yaml --set-string image.tag=<40-character sha> --atomic --wait --timeout 5m
```

Set the real hostname in `deploy/values/demo.yaml` first. Then, outside the cluster: the Cloudflare tunnel
route and DNS record for that hostname, and the redirect URI `https://<host>/auth/callback/google` on the
Google OAuth client.

A missing Secret leaves a pod in `CreateContainerConfigError`; with `--atomic` the release rolls back and the
event names the Secret. On the first install api can restart while Postgres initialises; the 5 minute
timeout covers it.

## Operations

| Task | Action |
|---|---|
| Release | Merge to `main`. The pipeline builds, tests, pushes the images and deploys. |
| Roll back | `helm rollback county -n county-demo` (`helm history county -n county-demo` lists releases). The previous image runs on the current schema because every migration stays compatible with the previous release (ADR 0009). |
| Read logs | `kubectl -n county-demo logs deploy/county-api -f` |
| Rotate a secret | Recreate it, then restart what reads it (below). |

Rotate a secret:

```bash
kubectl -n county-demo create secret generic county-web \
  --from-literal=AUTH_SECRET="$(openssl rand -base64 32)" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n county-demo rollout restart deployment/county-api deployment/county-web
```

Rotating `county-postgres` also needs the database role's password changed to match
(`ALTER ROLE county PASSWORD ...` through `kubectl exec` into `county-postgres-0`), and then a restart of
postgres as well. Rotate in that order: change the role, update the Secret, restart.

### Backups and restore

Backups are Longhorn recurring snapshots of the postgres volume, daily at 02:00 Asia/Manila, retain 7
(ADR 0008). The recurring job is a cluster-level object outside the namespace, so the chart does not create
it. Create it once, then label the claim. Longhorn evaluates `cron` in the manager's time zone, normally UTC,
so 02:00 in Manila is 18:00 UTC:

```bash
kubectl apply -f - <<'YAML'
apiVersion: longhorn.io/v1beta2
kind: RecurringJob
metadata: {name: county-daily, namespace: longhorn-system}
spec: {cron: "0 18 * * *", task: snapshot, retain: 7, concurrency: 1, groups: [county-daily]}
YAML
kubectl -n county-demo label pvc data-county-postgres-0 recurring-job-group.longhorn.io/county-daily=enabled
```

Snapshots are crash-consistent; Postgres recovers from one as from a power cut. A lost disk loses up to a
day. There is no `pg_dump` CronJob yet (deferred to the first pilot).

Restore a snapshot. The volume must be detached, so postgres stops as well as api:

```bash
kubectl -n county-demo scale deployment/county-api --replicas=0
kubectl -n county-demo scale statefulset/county-postgres --replicas=0
# Longhorn UI: attach the volume in maintenance mode, revert to the chosen snapshot, detach it.
kubectl -n county-demo scale statefulset/county-postgres --replicas=1
kubectl -n county-demo scale deployment/county-api --replicas=1
```

The next release sets the replica counts from the chart again. Rehearse this once before the first demo.
`helm uninstall` leaves the postgres claim in place (`persistentVolumeClaimRetentionPolicy: Retain`).

## The pipeline

`Jenkinsfile`, one pipeline for every branch, run by Jenkins in the cluster with the Kubernetes plugin. Every
branch tests, builds and pushes images tagged with the commit SHA. Only `main` deploys, with
`helm upgrade --install ... --atomic --wait --timeout 5m`. The deploy stage runs in a pod of its own, as the
`jenkins-deployer` ServiceAccount, so the build and test containers never hold cluster rights. Its smoke stage
reads the public URL (`GET /` is 200, `GET /api/me` is 401), because the NetworkPolicies keep other
namespaces away from api and web; `--wait` has already run their health probes.

Objects the operator creates once, in the namespace where Jenkins runs its agents (written here as `jenkins`):

```bash
# registry push credential, mounted as /kaniko/.docker/config.json in the Kaniko container only.
# GHCR_USER and GHCR_TOKEN (a token that can write packages) come from your shell, never from a file in git.
printf '{"auths":{"ghcr.io":{"auth":"%s"}}}' "$(printf '%s:%s' "$GHCR_USER" "$GHCR_TOKEN" | base64 -w0)" > /tmp/ghcr-config.json
kubectl -n jenkins create secret generic ghcr-push --from-file=config.json=/tmp/ghcr-config.json
rm /tmp/ghcr-config.json

kubectl -n jenkins create serviceaccount jenkins-deployer
```

The deployer's rights, in `county-demo` only. Helm keeps release state in Secrets, hence `secrets`. No
cluster-wide rights:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata: {name: jenkins-deployer, namespace: county-demo}
rules:
  - apiGroups: [""]
    resources: [configmaps, secrets, services, serviceaccounts, pods]
    verbs: [get, list, watch, create, update, patch, delete]
  - apiGroups: [""]
    resources: [persistentvolumeclaims]
    verbs: [get, list, watch]
  - apiGroups: [apps]
    resources: [deployments, statefulsets]
    verbs: [get, list, watch, create, update, patch, delete]
  - apiGroups: [apps]
    resources: [replicasets]
    verbs: [get, list, watch]
  - apiGroups: [networking.k8s.io]
    resources: [ingresses, networkpolicies]
    verbs: [get, list, watch, create, update, patch, delete]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata: {name: jenkins-deployer, namespace: county-demo}
roleRef: {apiGroup: rbac.authorization.k8s.io, kind: Role, name: jenkins-deployer}
subjects:
  - {kind: ServiceAccount, name: jenkins-deployer, namespace: jenkins}
```

Any build that can edit the `Jenkinsfile` can remove the `main` guard from the deploy stage, so only people
who may deploy should be able to push branches to this repository.

`.github/workflows/deploy.yml` runs the chart and pipeline tests and builds both images with
`deploy/tests/smoke.sh` on pull requests that touch these files. It pushes nothing and reaches no cluster.
Its `images` job prints the digest of the api runtime base image so that it can be pinned (see below).

## Rate limiting

Rate limiting lives at the Cloudflare edge, not in the API (ADR 0003, spec section 5). Recommended rule,
not required for the demo: Security, WAF, Rate limiting rules; expression
`starts_with(http.request.uri.path, "/api/order/")`; characteristic IP; 60 requests per 1 minute; action
Block for 1 minute. It makes probing of reseller link tokens expensive; unknown, blocked and expired tokens
already return the same 404.

## What is unverified

Written 2026-11-28, before any deploy.

- **Never installed.** The chart renders and lints with Helm v3.19.0, and its tests pass. No cluster has
  accepted it. Role verbs, NetworkPolicy behaviour on xeon's CNI, the Longhorn objects above and the
  Ingress class are untested. Cluster assumptions A1 to A6 are unconfirmed.
- **The `Jenkinsfile` has never run.** Its shell steps were checked with `bash -n` and the Helm download
  snippet and the smoke check were run on their own; the Groovy and the Kubernetes plugin behaviour were not.
  The first Jenkins run is the real test.
- **api runtime image.** `api/Dockerfile` runs on `registry.access.redhat.com/ubi9/openjdk-21-runtime:1.23`,
  pinned by tag only. That registry was unreachable when it was written, so the tag, the digest and the
  runtime stage were never built or run. The `images` job of `deploy.yml` is their first run; pin the digest
  it prints. The build stage, and the application started from its output on a Temurin JDK with a read-only
  root filesystem, were checked.
- **web image.** Built and run hardened with `deploy/tests/smoke.sh web`.
- **Kaniko.** The executor image is the community fork `ghcr.io/osscontainertools/kaniko:debug`, pinned by
  digest; ADR 0007 leaves the fork open. The `.dockerignore` handling and the cache repositories are untested.
- **Placeholder host.** `deploy/values/demo.yaml` holds `county.example.ph`.

## Left out on purpose

- No Micrometer, `/q/metrics`, ServiceMonitor or JSON log format: each is a new runtime dependency for a
  cluster without Prometheus (A6).
- The api access log stays off. Spec section 12 asks for it, but request paths contain reseller link tokens
  and `CLAUDE.md` forbids logging them.
- No `pg_dump` CronJob, HPA, PodDisruptionBudget, second environment, GitOps, sealed secrets, native image,
  `postgres.enabled=false` switch or `imagePullSecrets` value (spec section 13; add them when an environment
  needs them).
