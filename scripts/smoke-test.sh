#!/usr/bin/env bash
set -euo pipefail

for dependency in curl jq; do
  if ! command -v "$dependency" >/dev/null 2>&1; then
    echo "Required command not found: $dependency" >&2
    exit 2
  fi
done

catalog_base_url="${1:-http://127.0.0.1:${SERVER_PORT:-8080}}"
catalog_base_url="${catalog_base_url%/}"
catalog_temp_dir="$(mktemp -d)"
trap 'rm -rf -- "$catalog_temp_dir"' EXIT

curl --fail --silent --show-error --max-time 10 \
  "$catalog_base_url/actuator/health/readiness" | jq --exit-status '.status == "UP"' >/dev/null

# One POST, without automatic retry: this endpoint is not idempotent.
catalog_status="$(curl --silent --show-error --max-time 10 \
  -o "$catalog_temp_dir/created.json" -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  -d '{"title":"Camera","category":"Electronics"}' \
  "$catalog_base_url/api/v1/ads")"
if [[ "$catalog_status" != '201' ]]; then
  echo "Expected creation status 201; got $catalog_status" >&2
  cat "$catalog_temp_dir/created.json" >&2
  exit 1
fi

catalog_ad_id="$(jq --exit-status --raw-output '.id' "$catalog_temp_dir/created.json")"
curl --fail --silent --show-error --max-time 10 \
  "$catalog_base_url/api/v1/ads/$catalog_ad_id" > "$catalog_temp_dir/fetched.json"
jq --exit-status --slurp '.[0] == .[1]' \
  "$catalog_temp_dir/created.json" "$catalog_temp_dir/fetched.json" >/dev/null

catalog_status="$(curl --silent --show-error --max-time 10 \
  -o "$catalog_temp_dir/invalid.json" -w '%{http_code}' \
  -H 'Content-Type: application/json' -d '{"title":" ","category":"Electronics"}' \
  "$catalog_base_url/api/v1/ads")"
[[ "$catalog_status" == '400' ]]
jq --exit-status '.status == 400 and (.errors | length > 0)' \
  "$catalog_temp_dir/invalid.json" >/dev/null

echo "PASS: readiness, persisted create/read, and validation. Created ad: $catalog_ad_id"
