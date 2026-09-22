#!/usr/bin/env bash
set -euo pipefail
ranking_cutoff="${1:?Usage: export-training.sh AS_OF OUTPUT.jsonl [BASE_URL]}"
ranking_output="${2:?Provide a new output file}"
ranking_base="${3:-http://127.0.0.1:8080}"
: "${ADMIN_TOKEN:?Set ADMIN_TOKEN}"
command -v jq >/dev/null
[[ ! -e "$ranking_output" ]] || { echo 'Output already exists.' >&2; exit 2; }
mkdir -p "$(dirname -- "$ranking_output")"
ranking_work="$(mktemp -d "$(dirname -- "$ranking_output")/.export-XXXXXX")"
trap 'rm -rf -- "$ranking_work"' EXIT
: > "$ranking_work/rows.jsonl"
if command -v uuidgen >/dev/null; then ranking_export_id="$(uuidgen | tr '[:upper:]' '[:lower:]')"
else ranking_export_id="$(cat /proc/sys/kernel/random/uuid)"; fi
jq -n --arg id "$ranking_export_id" --arg cutoff "$ranking_cutoff" '{id:$id,asOf:$cutoff}' | \
  curl --fail-with-body --silent --show-error "$ranking_base/api/v1/admin/training-exports" \
    -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' --data-binary @- > "$ranking_work/export.json"
ranking_after=''
while :; do
  ranking_cursor=()
  [[ -z "$ranking_after" ]] || ranking_cursor=(--data-urlencode "after=$ranking_after")
  curl --fail-with-body --silent --show-error --get "$ranking_base/api/v1/admin/training-exports/$ranking_export_id" \
    -H "X-Admin-Token: $ADMIN_TOKEN" --data-urlencode 'limit=5000' \
    "${ranking_cursor[@]}" > "$ranking_work/page.json"
  jq -c '.rows[]' "$ranking_work/page.json" >> "$ranking_work/rows.jsonl"
  ranking_after="$(jq -r '.nextAfter // empty' "$ranking_work/page.json")"
  [[ -n "$ranking_after" ]] || break
done
# Hard link makes publication fail if another writer already created the destination.
ln "$ranking_work/rows.jsonl" "$ranking_output"
echo "Exported $(wc -l < "$ranking_output") mature examples to $ranking_output (snapshot $ranking_export_id)"
