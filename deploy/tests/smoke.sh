#!/usr/bin/env bash
# Image smoke test (I1, I2): builds the api and web images from the repository root, then runs
# each the way the chart does (read-only root filesystem, no capabilities, no privilege
# escalation, writable emptyDir-like tmpfs only) and checks it comes up.
#
#   deploy/tests/smoke.sh [api] [web]     default: both
#   DOCKER_BUILD_OPTS="--network host"    extra `docker build` flags, for restricted build networks
#
# Needs a Docker daemon. Never pushes an image. Credentials are generated per run.
set -euo pipefail

cd "$(dirname "$0")/../.."
targets=("$@")
[ ${#targets[@]} -gt 0 ] || targets=(api web)

run_id="county-smoke-$$"
network="$run_id"
password="$(openssl rand -hex 16)"
containers=()

cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then
    for c in "${containers[@]}"; do
      echo "--- logs of $c" >&2
      docker logs --tail 60 "$c" >&2 || true
    done
  fi
  for c in "${containers[@]}"; do docker rm -f "$c" >/dev/null 2>&1 || true; done
  docker network rm "$network" >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "ok: $*"; }

# shellcheck disable=SC2086
build() { docker build ${DOCKER_BUILD_OPTS:-} -f "$1/Dockerfile" -t "county-$1:smoke" .; }

start() { # name, docker run args...
  local name="$run_id-$1"; shift
  containers+=("$name")
  docker run -d --name "$name" --network "$network" "$@" >/dev/null
}

hardened=(--read-only --cap-drop ALL --security-opt no-new-privileges)

host_port() { docker port "$run_id-$1" "$2/tcp" | head -n 1 | sed 's/.*://'; }

status_of() { curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -m 5 "$1" || true; }

wait_for_status() { # container, url, expected, seconds
  local deadline=$((SECONDS + $4))
  while [ "$SECONDS" -lt "$deadline" ]; do
    [ "$(docker inspect -f '{{.State.Running}}' "$run_id-$1")" = true ] || fail "$1 container stopped"
    [ "$(status_of "$2")" = "$3" ] && return 0
    sleep 2
  done
  fail "$2 did not answer $3 within $4 seconds"
}

assert_numeric_non_root_user() {
  local user
  user="$(docker inspect -f '{{.Config.User}}' "county-$1:smoke")"
  [[ "$user" =~ ^[1-9][0-9]*(:[0-9]+)?$ ]] || fail "county-$1 image user is '$user', want a numeric non-root uid"
  ok "county-$1 runs as uid $user"
}

assert_no_read_only_errors() {
  if docker logs "$run_id-$1" 2>&1 | grep -qiE 'EROFS|read-only file system'; then
    fail "$1 wrote to the read-only filesystem"
  fi
  ok "$1 never wrote outside its writable path"
}

assert_no_junk() { # target, directory
  local found
  found="$(docker run --rm --entrypoint sh "county-$1:smoke" -c \
    "find $2 -maxdepth 2 \( -name .git -o -name '.env*' \) 2>/dev/null")"
  [ -z "$found" ] || fail "county-$1 image holds $found"
  ok "county-$1 holds no .git or .env file in $2"
}

smoke_api() {
  build api
  assert_numeric_non_root_user api
  assert_no_junk api /deployments
  local pg_image
  pg_image="$(grep -m1 '^  image: postgres:' deploy/county/values.yaml | sed 's/^  image: //')"
  start postgres -e POSTGRES_DB=county -e POSTGRES_USER=county \
    -e POSTGRES_PASSWORD="$password" "$pg_image"
  until docker exec "$run_id-postgres" pg_isready -U county -d county >/dev/null 2>&1; do sleep 1; done
  start api "${hardened[@]}" --tmpfs /tmp -p 127.0.0.1::8080 \
    -e COUNTY_JDBC_URL="jdbc:postgresql://$run_id-postgres:5432/county" \
    -e COUNTY_DB_USER=county -e COUNTY_DB_PASSWORD="$password" \
    -e GOOGLE_CLIENT_ID=smoke-test.apps.googleusercontent.com \
    -e COUNTY_PUBLIC_URL=https://county.example.ph "county-api:smoke"
  wait_for_status api "http://127.0.0.1:$(host_port api 8080)/q/health/ready" 200 120
  ok "api is ready with a read-only root filesystem"
  local applied
  applied="$(docker exec "$run_id-postgres" psql -U county -d county -tAc \
    'select count(*) from flyway_schema_history where success')"
  [ "${applied:-0}" -gt 0 ] || fail "no Flyway migration was applied"
  ok "$applied Flyway migrations applied"
  assert_no_read_only_errors api
}

smoke_web() {
  build web
  assert_numeric_non_root_user web
  assert_no_junk web /app
  start web "${hardened[@]}" --tmpfs /app/.next/cache:rw,mode=1777 -p 127.0.0.1::3000 \
    -e PORT=3000 -e HOSTNAME=0.0.0.0 \
    -e COUNTY_API_URL=http://api.invalid:8080/api \
    -e AUTH_URL=https://county.example.ph/auth -e AUTH_TRUST_HOST=true \
    -e AUTH_SECRET="$(openssl rand -hex 32)" \
    -e AUTH_GOOGLE_ID=smoke-test.apps.googleusercontent.com \
    -e AUTH_GOOGLE_SECRET="$(openssl rand -hex 16)" "county-web:smoke"
  local base="http://127.0.0.1:$(host_port web 3000)"
  wait_for_status web "$base/healthz" 200 60
  ok "web answers /healthz with no API running and a read-only root filesystem"
  [ "$(status_of "$base/")" = 200 ] || fail "web did not answer / with 200"
  ok "web serves /"
  assert_no_read_only_errors web
}

docker network create "$network" >/dev/null
for target in "${targets[@]}"; do
  case "$target" in
    api) smoke_api ;;
    web) smoke_web ;;
    *) fail "unknown target '$target', use api or web" ;;
  esac
done
echo "smoke test passed: ${targets[*]}"
