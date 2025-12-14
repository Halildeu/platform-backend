#!/usr/bin/env bash
set -euo pipefail

: "${GRAFANA_URL:?Set GRAFANA_URL (e.g. http://grafana:3000)}"
: "${GRAFANA_USER:=admin}"
: "${GRAFANA_PASS:=admin}"

say() { echo "[grafana] $*"; }

say "datasources:" && curl -s -u "$GRAFANA_USER:$GRAFANA_PASS" "$GRAFANA_URL/api/datasources" | jq -r '.[] | "- \(.name) (uid=\(.uid), type=\(.type))"'

say "rules (Operations / Reports Guard):" && curl -s -u "$GRAFANA_USER:$GRAFANA_PASS" "$GRAFANA_URL/api/ruler/grafana/api/v1/rules" | jq -r '."Operations / Reports Guard" // [] | .[] | select(.name=="reports-guard") | .rules[] | "- \(.grafana_alert.title) [uid=\(.grafana_alert.uid)]"'

say "active alerts (first 5):" && curl -s -u "$GRAFANA_USER:$GRAFANA_PASS" "$GRAFANA_URL/api/alertmanager/grafana/api/v2/alerts" | jq -r '.[0:5][] | "- \(.labels.rulename) => state=\(.status.state) severity=\(.labels.severity)"'

say "done"

