#!/usr/bin/env bash
# staging-error-sweep.sh — Proactive log scanner for staging services.
#
# 2026-04-19 QLTY-PROACTIVE-04 (Lane A MVP).
# Runs on the stage self-hosted GitHub Actions runner (or manually on stage box).
# Iterates 8 backend containers, greps recent logs for error-level patterns,
# applies an allowlist to suppress known-benign noise, emits a JSON summary
# and exits non-zero when per-service error count exceeds thresholds.
#
# Environment variables:
#   WINDOW_MINUTES  (default: 30)  — how far back to scan docker logs
#   WARN_THRESHOLD  (default: 3)   — errors per service to trigger WARN verdict
#   FAIL_THRESHOLD  (default: 10)  — errors per service to trigger FAIL verdict
#   OUTPUT_PATH     (default: .cache/reports/staging-error-sweep-<ts>.json)
#   ALLOWLIST_PATH  (default: backend/scripts/ops/staging-error-allowlist.txt)
#   SERVICES        (comma-separated override; default: 8 standard services)
#
# Exit codes:
#   0 = PASS (all services below WARN_THRESHOLD)
#   1 = WARN (any service in [WARN_THRESHOLD, FAIL_THRESHOLD))
#   2 = FAIL (any service >= FAIL_THRESHOLD)

set -euo pipefail

WINDOW_MINUTES="${WINDOW_MINUTES:-30}"
WARN_THRESHOLD="${WARN_THRESHOLD:-3}"
FAIL_THRESHOLD="${FAIL_THRESHOLD:-10}"
ALLOWLIST_PATH="${ALLOWLIST_PATH:-backend/scripts/ops/staging-error-allowlist.txt}"

DEFAULT_SERVICES="api-gateway,auth-service,user-service,permission-service,variant-service,core-data-service,report-service,schema-service"
SERVICES="${SERVICES:-$DEFAULT_SERVICES}"

TS="$(date -u +%Y-%m-%dT%H-%M-%S)"
DEFAULT_OUT=".cache/reports/staging-error-sweep-${TS}.json"
OUTPUT_PATH="${OUTPUT_PATH:-$DEFAULT_OUT}"

# Error patterns we care about. Each pattern is an extended regex.
# Exclude DEBUG and plain INFO lines to reduce noise; include FATAL, ERROR,
# Exception names, HTTP 5xx, and security rejections.
ERROR_REGEX='(^|[[:space:]])(FATAL|ERROR|EXCEPTION|Exception)|(HTTP\/[0-9.]+ 5[0-9]{2})|(rejected|denied|unauthorized|forbidden)'

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[sweep] FATAL: required command '$1' not found" >&2
    exit 2
  fi
}

require_cmd docker
require_cmd python3

# Resolve allowlist path relative to repo root if it's relative.
if [[ ! -f "${ALLOWLIST_PATH}" ]] && [[ -f "$(dirname "$0")/staging-error-allowlist.txt" ]]; then
  ALLOWLIST_PATH="$(dirname "$0")/staging-error-allowlist.txt"
fi

if [[ -f "${ALLOWLIST_PATH}" ]]; then
  # Build a grep -E -v filter string by joining non-comment non-empty lines with |.
  ALLOW_REGEX="$(
    grep -Ev '^(\s*#|\s*$)' "${ALLOWLIST_PATH}" \
      | tr '\n' '|' \
      | sed 's/|$//'
  )"
else
  ALLOW_REGEX=""
fi

mkdir -p "$(dirname "${OUTPUT_PATH}")"

# Build a per-service JSON fragment and accumulate overall verdict.
python_in="$(mktemp)"
trap 'rm -f "${python_in}"' EXIT

max_count=0
verdict="pass"
echo '{"services":[' > "${python_in}"
first=1

IFS=',' read -r -a svc_array <<< "${SERVICES}"
for svc in "${svc_array[@]}"; do
  container="platform-${svc}-1"
  if ! docker inspect "${container}" >/dev/null 2>&1; then
    # Missing container — record but don't fail (may be intentionally off).
    sample_json='[]'
    count=0
    status="missing"
  else
    raw_logs="$(docker logs --since "${WINDOW_MINUTES}m" "${container}" 2>&1 || true)"
    filtered_logs="$(printf '%s\n' "${raw_logs}" | grep -Ei "${ERROR_REGEX}" || true)"
    if [[ -n "${ALLOW_REGEX}" ]]; then
      filtered_logs="$(printf '%s\n' "${filtered_logs}" | grep -Ev "${ALLOW_REGEX}" || true)"
    fi
    # Trim blanks.
    filtered_logs="$(printf '%s\n' "${filtered_logs}" | sed '/^$/d')"
    count="$(printf '%s' "${filtered_logs}" | grep -c . || true)"
    # Keep last 5 samples as array of single-line strings.
    sample_json="$(
      printf '%s\n' "${filtered_logs}" \
        | tail -n 5 \
        | python3 -c 'import json, sys; lines = [ln.rstrip() for ln in sys.stdin if ln.strip()]; print(json.dumps(lines[-5:]))'
    )"
    status="ok"
  fi

  if [[ "${count}" -gt "${max_count}" ]]; then
    max_count="${count}"
  fi

  if [[ "${count}" -ge "${FAIL_THRESHOLD}" ]]; then
    verdict="fail"
  elif [[ "${count}" -ge "${WARN_THRESHOLD}" && "${verdict}" != "fail" ]]; then
    verdict="warn"
  fi

  if [[ "${first}" -eq 0 ]]; then
    echo "," >> "${python_in}"
  fi
  first=0

  python3 -c "
import json, sys
print(json.dumps({
  'name': '${svc}',
  'container': '${container}',
  'status': '${status}',
  'errorCount': int('${count}'),
  'samples': json.loads('''${sample_json}''') if '''${sample_json}''' else [],
}, indent=2))
" >> "${python_in}"
done

echo "]," >> "${python_in}"
python3 -c "
import json
print(json.dumps({
  'generatedAt': '${TS}Z',
  'windowMinutes': int('${WINDOW_MINUTES}'),
  'warnThreshold': int('${WARN_THRESHOLD}'),
  'failThreshold': int('${FAIL_THRESHOLD}'),
  'maxServiceErrorCount': int('${max_count}'),
  'verdict': '${verdict}',
})[1:-1])
" >> "${python_in}"
echo "}" >> "${python_in}"

# Clean up JSON (the concat-by-hand above is brittle — let python validate/reformat).
python3 -c "
import json, sys, pathlib
raw = pathlib.Path('${python_in}').read_text()
# Reparse to validate and pretty-print.
obj = json.loads(raw)
pathlib.Path('${OUTPUT_PATH}').write_text(json.dumps(obj, indent=2, sort_keys=True) + '\n')
print(f'[sweep] report written: ${OUTPUT_PATH}')
print(f'[sweep] verdict=${verdict} max={max_count} window=${WINDOW_MINUTES}m')
for svc in obj['services']:
  flag = 'OK' if svc['errorCount'] == 0 else ('MISS' if svc['status']=='missing' else ('!!' if svc['errorCount'] >= ${FAIL_THRESHOLD} else '**'))
  print(f\"[sweep]  {flag:4s} {svc['name']:24s} errors={svc['errorCount']}\")
"

case "${verdict}" in
  pass) exit 0 ;;
  warn) exit 1 ;;
  fail) exit 2 ;;
esac
