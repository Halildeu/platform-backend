#!/usr/bin/env bash
set -euo pipefail
# SK-1 + SK-5 Telemetry Baseline — CNS-20260411-003 PR2-b
# Measures OpenFGA availability and decision log coverage against Prometheus.
# Usage: bash backend/scripts/ci/zanzibar-sk-baseline.sh [--json]

PROM_URL="${PROMETHEUS_URL:-http://localhost:9090}"
JSON_MODE=0
[[ "${1:-}" == "--json" ]] && JSON_MODE=1

query_prom() {
  local query="$1"
  curl -sf "${PROM_URL}/api/v1/query" --data-urlencode "query=${query}" 2>/dev/null
}

extract_value() {
  python3 -c "
import sys, json
d = json.load(sys.stdin)
results = d.get('data', {}).get('result', [])
total = sum(float(r['value'][1]) for r in results)
print(f'{total:.0f}')
"
}

# --- SK-1: OpenFGA check availability ---
total_checks=$(query_prom 'sum(http_server_requests_seconds_count{uri=~"/v1/authz/check|/api/v1/authz/check|/v1/authz/batch-check|/api/v1/authz/batch-check"})' | extract_value)
error_checks=$(query_prom 'sum(http_server_requests_seconds_count{uri=~"/v1/authz/check|/api/v1/authz/check|/v1/authz/batch-check|/api/v1/authz/batch-check",status=~"5.."})' 2>/dev/null | extract_value || echo "0")
openfga_up=$(query_prom 'up{job="openfga"}' | extract_value || echo "0")

if [[ "${total_checks}" -gt 0 ]]; then
  success_checks=$((total_checks - error_checks))
  sk1_pct=$(python3 -c "print(f'{${success_checks}/${total_checks}*100:.2f}')")
else
  sk1_pct="N/A (no checks recorded)"
fi

# --- SK-5: Decision log coverage ---
# Check if authz endpoints return audit-worthy responses (logged via actuator/micrometer)
total_authz_endpoints=$(query_prom 'count(http_server_requests_seconds_count{uri=~"/v1/authz/.*|/api/v1/authz/.*"})' | extract_value)
me_calls=$(query_prom 'sum(http_server_requests_seconds_count{uri=~"/v1/authz/me|/api/v1/authz/me"})' | extract_value)
version_calls=$(query_prom 'sum(http_server_requests_seconds_count{uri=~"/v1/authz/version|/api/v1/authz/version"})' | extract_value)
check_calls="${total_checks}"

# --- Output ---
if [[ "${JSON_MODE}" -eq 1 ]]; then
  cat <<JSONEOF
{
  "sk1_availability": {
    "total_checks": ${total_checks:-0},
    "error_checks": ${error_checks:-0},
    "success_rate_pct": "${sk1_pct}",
    "openfga_up": ${openfga_up:-0},
    "target": "99.9%"
  },
  "sk5_decision_log": {
    "total_authz_endpoints": ${total_authz_endpoints:-0},
    "me_calls": ${me_calls:-0},
    "version_calls": ${version_calls:-0},
    "check_calls": ${check_calls:-0},
    "note": "All authz endpoints are instrumented via Spring Boot Actuator (http_server_requests_seconds). Decision-level audit logging requires ReportAccessEvaluator audit trail — not yet measured.",
    "target": "100%"
  },
  "baseline_date": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "prometheus_url": "${PROM_URL}"
}
JSONEOF
else
  echo "=== SK-1: OpenFGA Check Availability ==="
  echo "  Total checks:  ${total_checks:-0}"
  echo "  Error checks:  ${error_checks:-0}"
  echo "  Success rate:  ${sk1_pct}"
  echo "  OpenFGA up:    ${openfga_up:-0}"
  echo "  Target:        >= 99.9%"
  echo ""
  echo "=== SK-5: Decision Log Coverage ==="
  echo "  Authz endpoints: ${total_authz_endpoints:-0}"
  echo "  /me calls:       ${me_calls:-0}"
  echo "  /version calls:  ${version_calls:-0}"
  echo "  /check calls:    ${check_calls:-0}"
  echo "  Note: Spring Boot Actuator instruments all endpoints."
  echo "  Target:          100%"
fi
