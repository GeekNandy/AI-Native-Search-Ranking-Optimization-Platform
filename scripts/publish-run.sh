#!/usr/bin/env bash
set -euo pipefail
ranking_run="${1:?Usage: publish-run.sh RUN_DIRECTORY [BASE_URL]}"
ranking_base="${2:-http://127.0.0.1:8080}"
: "${ADMIN_TOKEN:?Set ADMIN_TOKEN}"
for ranking_file in model.json features.json complete.json; do
  [[ -f "$ranking_run/$ranking_file" ]] || { echo "Incomplete run: $ranking_file is missing" >&2; exit 2; }
done
ranking_model_hash="$(sha256sum "$ranking_run/model.json" | cut -d' ' -f1)"
ranking_feature_hash="$(sha256sum "$ranking_run/features.json" | cut -d' ' -f1)"
jq -e --arg model "$ranking_model_hash" --arg snapshot "$ranking_feature_hash" \
  '.modelSha256 == $model and .snapshotSha256 == $snapshot' "$ranking_run/complete.json" >/dev/null
curl --fail-with-body --silent --show-error "$ranking_base/api/v1/admin/models" \
  -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' --data-binary "@$ranking_run/model.json"
echo
curl --fail-with-body --silent --show-error "$ranking_base/api/v1/admin/features/snapshots" \
  -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' --data-binary "@$ranking_run/features.json"
echo
echo 'Artifacts registered. Use the deployment API to select a model and rollout percentage.'
