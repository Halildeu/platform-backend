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
#
# Design note: JSON is generated entirely in Python (via file-backed inputs)
# to avoid the shell-concat quoting fragility seen in v1.

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

# Error patterns we care about (extended regex, case-insensitive).
# Spring Boot log format: `<TIMESTAMP> <LEVEL> <PID> --- [<SERVICE>] ...`
# — match lines where LEVEL is FATAL, ERROR, or WARN (WARN included to catch
# rejected tokens and filter-chain warnings). Secondary patterns catch
# stack-trace markers and HTTP 5xx tokens that may not have the LEVEL prefix.
# DEBUG / INFO lines are explicitly excluded to cut ~99% of noise.
ERROR_REGEX='[[:space:]](FATAL|ERROR|WARN)[[:space:]]+[0-9]+[[:space:]]+---'
ERROR_REGEX="${ERROR_REGEX}"'|(^|[[:space:]])(Caused by:|at [a-z0-9_.$]+\()'
ERROR_REGEX="${ERROR_REGEX}"'|(HTTP\/[0-9.]+ 5[0-9]{2})'

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[sweep] FATAL: required command '$1' not found" >&2
    exit 2
  fi
}

require_cmd docker
require_cmd python3

# Resolve allowlist path relative to script dir if not found at given path.
if [[ ! -f "${ALLOWLIST_PATH}" ]] && [[ -f "$(dirname "$0")/staging-error-allowlist.txt" ]]; then
  ALLOWLIST_PATH="$(dirname "$0")/staging-error-allowlist.txt"
fi

# Build combined allow-regex (OR of all non-comment, non-blank patterns).
if [[ -f "${ALLOWLIST_PATH}" ]]; then
  ALLOW_REGEX="$(
    grep -Ev '^([[:space:]]*#|[[:space:]]*$)' "${ALLOWLIST_PATH}" \
      | tr '\n' '|' \
      | sed 's/|$//'
  )"
else
  ALLOW_REGEX=""
fi

mkdir -p "$(dirname "${OUTPUT_PATH}")"

# Work-directory for per-service filtered-log files.
work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT

IFS=',' read -r -a svc_array <<< "${SERVICES}"

manifest_file="${work_dir}/manifest.txt"
: > "${manifest_file}"

for svc in "${svc_array[@]}"; do
  container="platform-${svc}-1"
  svc_file="${work_dir}/${svc}.log"
  status="ok"

  if ! docker inspect "${container}" >/dev/null 2>&1; then
    : > "${svc_file}"
    status="missing"
  else
    # Capture raw logs for the window; tolerate docker-logs errors.
    raw_logs_file="${work_dir}/${svc}.raw.log"
    docker logs --since "${WINDOW_MINUTES}m" "${container}" > "${raw_logs_file}" 2>&1 || true

    # Filter for error patterns.
    grep -Ei "${ERROR_REGEX}" "${raw_logs_file}" > "${svc_file}" || true

    # Apply allowlist if present.
    if [[ -n "${ALLOW_REGEX}" ]]; then
      tmp="${work_dir}/${svc}.allow.log"
      grep -Ev "${ALLOW_REGEX}" "${svc_file}" > "${tmp}" || true
      mv "${tmp}" "${svc_file}"
    fi

    # Drop blank lines.
    sed -i '/^[[:space:]]*$/d' "${svc_file}"
  fi

  # Append to manifest: "svc<TAB>container<TAB>status<TAB>relpath"
  printf '%s\t%s\t%s\t%s\n' "${svc}" "${container}" "${status}" "${svc_file}" >> "${manifest_file}"
done

# Let Python build the JSON and decide the verdict.
verdict="$(
  python3 - "${manifest_file}" "${OUTPUT_PATH}" "${TS}" "${WINDOW_MINUTES}" "${WARN_THRESHOLD}" "${FAIL_THRESHOLD}" <<'PY'
import json
import pathlib
import sys

manifest_path, output_path, ts, window_m, warn_t, fail_t = sys.argv[1:7]
window_m = int(window_m)
warn_t = int(warn_t)
fail_t = int(fail_t)

services = []
max_count = 0
verdict = "pass"

for line in pathlib.Path(manifest_path).read_text().splitlines():
    if not line.strip():
        continue
    parts = line.split("\t")
    if len(parts) != 4:
        continue
    svc, container, status, logf = parts
    if status == "missing":
        count = 0
        samples = []
    else:
        content = pathlib.Path(logf).read_text(errors="replace")
        lines = [ln.rstrip() for ln in content.splitlines() if ln.strip()]
        count = len(lines)
        samples = lines[-5:]
    services.append({
        "name": svc,
        "container": container,
        "status": status,
        "errorCount": count,
        "samples": samples,
    })
    if count > max_count:
        max_count = count
    if count >= fail_t:
        verdict = "fail"
    elif count >= warn_t and verdict != "fail":
        verdict = "warn"

summary = {
    "generatedAt": ts + "Z",
    "windowMinutes": window_m,
    "warnThreshold": warn_t,
    "failThreshold": fail_t,
    "maxServiceErrorCount": max_count,
    "verdict": verdict,
    "services": services,
}

out = pathlib.Path(output_path)
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")

print(f"[sweep] report written: {output_path}", file=sys.stderr)
print(f"[sweep] verdict={verdict} max={max_count} window={window_m}m", file=sys.stderr)
for svc in services:
    if svc["status"] == "missing":
        flag = "MISS"
    elif svc["errorCount"] >= fail_t:
        flag = "!!"
    elif svc["errorCount"] >= warn_t:
        flag = "**"
    elif svc["errorCount"] > 0:
        flag = "."
    else:
        flag = "OK"
    print(f"[sweep]  {flag:4s} {svc['name']:24s} errors={svc['errorCount']}", file=sys.stderr)

# stdout carries the verdict for the outer shell to consume.
print(verdict)
PY
)"

case "${verdict}" in
  pass) exit 0 ;;
  warn) exit 1 ;;
  fail) exit 2 ;;
  *)
    echo "[sweep] FATAL: unknown verdict '${verdict}'" >&2
    exit 2
    ;;
esac
