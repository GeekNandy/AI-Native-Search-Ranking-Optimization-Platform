#!/usr/bin/env bash
set -euo pipefail
ranking_version="${1:?Usage: promote-model.sh MODEL_VERSION PERCENT [BASE_URL]}"
ranking_percent="${2:?Provide a rollout percentage from 0 to 100}"
ranking_base="${3:-http://127.0.0.1:8080}"
: "${ADMIN_TOKEN:?Set ADMIN_TOKEN}"
[[ "$ranking_percent" =~ ^[0-9]{1,3}$ ]] && (( 10#$ranking_percent <= 100 )) || { echo 'Invalid percentage' >&2; exit 2; }
ranking_percent="$((10#$ranking_percent))"
ranking_revision="$(curl --fail-with-body --silent --show-error "$ranking_base/api/v1/admin/deployment" \
  -H "X-Admin-Token: $ADMIN_TOKEN" | jq -er '.revision')"
jq -n --arg model "$ranking_version" --argjson revision "$ranking_revision" --argjson percent "$ranking_percent" \
  '{revision:$revision,modelVersion:$model,rolloutPercent:$percent}' | \
  curl --fail-with-body --silent --show-error -X PUT "$ranking_base/api/v1/admin/deployment" \
    -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' --data-binary @-
echo
