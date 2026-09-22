#!/usr/bin/env bash
set -euo pipefail
# Runs only against a disposable Compose project: this check stops and restarts PostgreSQL.
ranking_base="${1:-http://127.0.0.1:8080}"
ranking_tmp="$(mktemp -d)"
trap 'rm -rf -- "$ranking_tmp"' EXIT
ranking_wait_status() {
  local path="$1" expected="$2"
  for attempt in $(seq 1 45); do
    if [[ "$(curl --silent --max-time 6 --output /dev/null --write-out '%{http_code}' "$ranking_base$path" || true)" == "$expected" ]]; then return; fi
    sleep 1
  done
  echo "Did not observe $expected for $path" >&2; return 1
}
ranking_wait_status /actuator/health/readiness 200
curl --fail-with-body --silent --show-error "$ranking_base/api/v1/ads" -H 'Content-Type: application/json' \
  -d '{"title":"Persistence check","category":"Verification"}' > "$ranking_tmp/created.json"
ranking_id="$(jq -er .id "$ranking_tmp/created.json")"
docker compose stop postgres
ranking_wait_status /actuator/health/readiness 503
ranking_wait_status /actuator/health/liveness 200
ranking_wait_status "/api/v1/ads/$ranking_id" 503
docker compose up -d --wait postgres
ranking_wait_status /actuator/health/readiness 200
curl --fail --silent "$ranking_base/api/v1/ads/$ranking_id" > "$ranking_tmp/fetched.json"
jq -S . "$ranking_tmp/created.json" > "$ranking_tmp/expected.json"
jq -S . "$ranking_tmp/fetched.json" > "$ranking_tmp/actual.json"
cmp "$ranking_tmp/expected.json" "$ranking_tmp/actual.json"
echo 'Runtime database outage, recovery, and persistence checks passed.'
