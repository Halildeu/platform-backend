#!/usr/bin/env bash
# staging-audit-compare-sweep.sh — daily parity-evidence sweep for audit cutover.
#
# 2026-04-20 QLTY-PROACTIVE-06 Faz 2 monitoring lane.
# Polls /api/audit/events/compare and records the verdict to an artifact.
# Used to accumulate multi-day evidence before Faz 4 (read-path cleanup) and
# Faz 5 (table drop) can ship safely.
#
# Runs on the stage self-hosted GitHub Actions runner (has docker + curl).
#
# Environment:
#   BASE_URL                (default https://ai.acik.com)
#   KC_URL                  (default https://ai.acik.com/realms/serban)
#   ADMIN_USER              (default admin@example.com)
#   ADMIN_PASS              (required — from GH secret / stage canonical env)
#   SWEEPER_CLIENT_ID       (default staging-sweeper)
#   SWEEPER_CLIENT_SECRET   (required — from GH secret STAGING_SWEEPER_CLIENT_SECRET)
#   PAGE                    (default 1)
#   PAGE_SIZE               (default 50)
#   OUTPUT_PATH             (default .cache/reports/audit-compare-sweep-<ts>.json)
#
# Exit codes:
#   0 = verdict `clean`
#   1 = verdict `id-drift` or `count-drift` or `field-drift` (soft warn)
#   2 = verdict `user-service-unreachable` or script failure (hard fail)

set -euo pipefail

BASE_URL="${BASE_URL:-https://ai.acik.com}"
KC_URL="${KC_URL:-https://ai.acik.com/realms/serban}"
ADMIN_USER="${ADMIN_USER:-admin@example.com}"
SWEEPER_CLIENT_ID="${SWEEPER_CLIENT_ID:-staging-sweeper}"
PAGE="${PAGE:-1}"
PAGE_SIZE="${PAGE_SIZE:-50}"

TS="$(date -u +%Y-%m-%dT%H-%M-%S)"
DEFAULT_OUT=".cache/reports/audit-compare-sweep-${TS}.json"
OUTPUT_PATH="${OUTPUT_PATH:-$DEFAULT_OUT}"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[compare-sweep] FATAL: required command '$1' not found" >&2
    exit 2
  fi
}

require_cmd curl
require_cmd python3

if [[ -z "${ADMIN_PASS:-}" ]]; then
  echo "[compare-sweep] FATAL: ADMIN_PASS env required" >&2
  exit 2
fi
if [[ -z "${SWEEPER_CLIENT_SECRET:-}" ]]; then
  echo "[compare-sweep] FATAL: SWEEPER_CLIENT_SECRET env required" >&2
  exit 2
fi

mkdir -p "$(dirname "${OUTPUT_PATH}")"

# Mint sweeper token.
token_response="$(
  curl -sS -w '\n%{http_code}' -X POST "${KC_URL}/protocol/openid-connect/token" \
    -d "grant_type=password" \
    -d "client_id=${SWEEPER_CLIENT_ID}" \
    -d "client_secret=${SWEEPER_CLIENT_SECRET}" \
    -d "username=${ADMIN_USER}" \
    -d "password=${ADMIN_PASS}" \
    -d "scope=openid profile email"
)"
token_status="$(printf '%s' "${token_response}" | tail -n 1)"
token_body="$(printf '%s' "${token_response}" | sed '$d')"

if [[ "${token_status}" != "200" ]]; then
  echo "[compare-sweep] FATAL: KC token mint failed (status=${token_status})" >&2
  echo "${token_body}" | head -c 300 >&2
  exit 2
fi

access_token="$(printf '%s' "${token_body}" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("access_token",""))')"
if [[ -z "${access_token}" ]]; then
  echo "[compare-sweep] FATAL: access_token missing in KC response" >&2
  exit 2
fi

# Call compare endpoint.
compare_response="$(
  curl -sS -w '\n%{http_code}' \
    -H "Authorization: Bearer ${access_token}" \
    -H "Accept: application/json" \
    "${BASE_URL}/api/audit/events/compare?page=${PAGE}&pageSize=${PAGE_SIZE}"
)"
compare_status="$(printf '%s' "${compare_response}" | tail -n 1)"
compare_body="$(printf '%s' "${compare_response}" | sed '$d')"

if [[ "${compare_status}" != "200" ]]; then
  echo "[compare-sweep] FATAL: compare endpoint returned HTTP ${compare_status}" >&2
  echo "${compare_body}" | head -c 300 >&2
  # Persist the failure for observability.
  python3 -c "
import json
summary = {
    'generatedAt': '${TS}Z',
    'compareHttpStatus': int('${compare_status}'),
    'verdict': 'endpoint-error',
    'error': 'compare endpoint returned non-200',
    'page': int('${PAGE}'),
    'pageSize': int('${PAGE_SIZE}'),
}
with open('${OUTPUT_PATH}', 'w') as f:
    json.dump(summary, f, indent=2, sort_keys=True)
    f.write('\n')
print('[compare-sweep] error report written:', '${OUTPUT_PATH}')
" >&2
  exit 2
fi

# Parse + summarize.
python3 - "${compare_body}" "${OUTPUT_PATH}" "${TS}" <<'PY'
import json
import pathlib
import sys

body, output_path, ts = sys.argv[1:4]
data = json.loads(body)
diff = data.get("diff", {})

summary = {
    "generatedAt": ts + "Z",
    "page": data.get("page"),
    "pageSize": data.get("pageSize"),
    "verdict": diff.get("verdict", "unknown"),
    "permissionTotal": diff.get("permissionTotal", 0),
    "userServiceTotal": diff.get("userServiceTotal", -1),
    "totalDelta": diff.get("totalDelta", 0),
    "permissionOnlyCount": len(diff.get("permissionOnlyIds", [])),
    "userServiceOnlyCount": len(diff.get("userServiceOnlyIds", [])),
    "commonCount": len(diff.get("commonIds", [])),
    "fieldDiffCount": len(diff.get("fieldDiffs", [])),
    "fieldDiffSamples": diff.get("fieldDiffs", [])[:5],
    "userServiceErrors": data.get("userServiceErrors", []),
}

# Also embed full diff for forensics (truncated ids list if huge).
full_diff = dict(diff)
for key in ("permissionOnlyIds", "userServiceOnlyIds", "commonIds"):
    lst = full_diff.get(key, [])
    if isinstance(lst, list) and len(lst) > 200:
        full_diff[key] = lst[:200] + [f"... truncated ({len(lst)-200} more)"]
summary["diff"] = full_diff

pathlib.Path(output_path).parent.mkdir(parents=True, exist_ok=True)
with open(output_path, "w") as f:
    json.dump(summary, f, indent=2, sort_keys=True)
    f.write("\n")

print(f"[compare-sweep] report written: {output_path}", file=sys.stderr)
print(f"[compare-sweep] verdict={summary['verdict']}", file=sys.stderr)
print(f"[compare-sweep]   permTotal={summary['permissionTotal']} userTotal={summary['userServiceTotal']} delta={summary['totalDelta']}", file=sys.stderr)
print(f"[compare-sweep]   permOnly={summary['permissionOnlyCount']} userOnly={summary['userServiceOnlyCount']} common={summary['commonCount']} fieldDiffs={summary['fieldDiffCount']}", file=sys.stderr)
if summary["userServiceErrors"]:
    print(f"[compare-sweep]   userServiceErrors={summary['userServiceErrors']}", file=sys.stderr)

# Exit code mapping.
verdict = summary["verdict"]
if verdict == "clean":
    sys.exit(0)
if verdict in ("id-drift", "count-drift", "field-drift"):
    sys.exit(1)
# user-service-unreachable, endpoint-error, unknown, etc.
sys.exit(2)
PY
