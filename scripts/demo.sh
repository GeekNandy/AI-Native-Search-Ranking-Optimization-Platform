#!/usr/bin/env bash
set -euo pipefail
ranking_root="$(cd -- "$(dirname -- "$0")/.." && pwd)"
ranking_base="${1:-http://127.0.0.1:8080}"
: "${ADMIN_TOKEN:?Set ADMIN_TOKEN to the same token used by the application}"
: "${SPARK_HOME:?Set SPARK_HOME}"
ranking_run_id="$(date -u +%Y%m%dT%H%M%SZ)-$RANDOM"
ranking_uuid() {
  if command -v uuidgen >/dev/null; then uuidgen | tr '[:upper:]' '[:lower:]'
  else cat /proc/sys/kernel/random/uuid; fi
}
ranking_output="$ranking_root/demo-output/$ranking_run_id"
mkdir -p "$ranking_output"
curl --fail --silent "$ranking_base/actuator/health/readiness" >/dev/null
for ranking_item in Alpha Beta Gamma Delta Epsilon Zeta Eta Theta; do
  jq -n --arg title "DemoCamera $ranking_item" '{title:$title,category:"Cameras"}' | \
    curl --fail-with-body --silent --show-error "$ranking_base/api/v1/ads" -H 'Content-Type: application/json' --data-binary @- | \
    jq -c . >> "$ranking_output/catalog.jsonl"
done
ranking_cutoff="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
ranking_snapshot="$(ranking_uuid)"
bash "$ranking_root/analytics/run.sh" --synthetic --catalog "$ranking_output/catalog.jsonl" \
  --output "$ranking_output/run" --as-of "$ranking_cutoff" --version "demo-$ranking_run_id" --snapshot-id "$ranking_snapshot"
bash "$ranking_root/scripts/publish-run.sh" "$ranking_output/run" "$ranking_base"
bash "$ranking_root/scripts/promote-model.sh" "demo-$ranking_run_id" 100 "$ranking_base"
curl --fail-with-body --silent --show-error "$ranking_base/api/v1/search" -H 'Content-Type: application/json' \
  -d '{"query":"DemoCamera","userId":"demo-user","limit":5}' | tee "$ranking_output/search.json" | jq .
jq -e --arg version "demo-$ranking_run_id" --arg snapshot "$ranking_snapshot" \
  '.policy == "model" and .modelVersion == $version and .snapshotId == $snapshot
   and .fallbackReason == null and (.items | length) == 5
   and all(.items[]; .score >= 0 and .score <= 1)' "$ranking_output/search.json" >/dev/null
ranking_impression="$(ranking_uuid)"
jq -n --arg id "$ranking_impression" --arg request "$(jq -r .requestId "$ranking_output/search.json")" \
  --arg ad "$(jq -r '.items[0].adId' "$ranking_output/search.json")" --arg now "$(jq -r '.createdAt' "$ranking_output/search.json")" \
  '{id:$id,requestId:$request,adId:$ad,occurredAt:$now}' > "$ranking_output/impression.json"
curl --fail-with-body --silent --show-error "$ranking_base/api/v1/events/impressions" -H 'Content-Type: application/json' \
  --data-binary "@$ranking_output/impression.json" | jq .
jq -n --arg id "$(ranking_uuid)" --arg impression "$ranking_impression" \
  --arg now "$(jq -r '.occurredAt' "$ranking_output/impression.json")" '{id:$id,impressionId:$impression,occurredAt:$now}' | \
  curl --fail-with-body --silent --show-error "$ranking_base/api/v1/events/clicks" -H 'Content-Type: application/json' --data-binary @- | jq .
echo "Synthetic demonstration complete: $ranking_output"
echo 'The new live exposure becomes eligible for training after 25 hours. Synthetic metrics are not business results.'
