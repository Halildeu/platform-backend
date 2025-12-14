#!/usr/bin/env bash
set -euo pipefail

: "${GRAFANA_URL:?Set GRAFANA_URL (e.g. http://localhost:3010)}"
: "${GRAFANA_USER:=admin}"
: "${GRAFANA_PASS:=admin}"

say() { echo "[grafana] $*"; }

say "fetching alertmanager config..."
TMP=$(mktemp)
curl -s -u "$GRAFANA_USER:$GRAFANA_PASS" \
  "$GRAFANA_URL/api/alertmanager/grafana/config" > "$TMP"

CURRENT=$(jq -r '.route.receiver // ""' "$TMP")
if [[ -n "$CURRENT" && "$CURRENT" != "null" ]]; then
  say "default route.receiver already set to: $CURRENT"
  rm -f "$TMP"
  exit 0
fi

say "setting default route.receiver=default-email"
UPDATED=$(jq '.route.receiver = "default-email"' "$TMP")

curl -s -u "$GRAFANA_USER:$GRAFANA_PASS" \
  -H 'Content-Type: application/json' \
  -X POST "$GRAFANA_URL/api/alertmanager/grafana/config" \
  -d "$UPDATED" >/dev/null

say "done"

