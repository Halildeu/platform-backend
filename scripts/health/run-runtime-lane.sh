#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." &> /dev/null && pwd)
REPO_ROOT=$(cd -- "$ROOT_DIR/.." &> /dev/null && pwd)
REPORT_PATH="${BACKEND_RUNTIME_GUARD_REPORT:-$REPO_ROOT/.cache/reports/backend_runtime_guard.v1.json}"

cleanup() {
  STOP_INFRA=1 "$ROOT_DIR/scripts/stop-services.sh" >/dev/null 2>&1 || true
}

trap cleanup EXIT

STOP_INFRA=1 "$ROOT_DIR/scripts/stop-services.sh" >/dev/null 2>&1 || true
AUTO_START_INFRA=1 \
RUN_SERVICES_POSTCHECK=1 \
RUN_SERVICES_STRICT_WARNINGS=1 \
RUN_SERVICES_FILTER="${RUN_SERVICES_FILTER:-all}" \
STARTUP_GUARD_REPORT="$REPORT_PATH" \
"$ROOT_DIR/scripts/run-services.sh"

echo "[ok] backend runtime lane report: $REPORT_PATH"
