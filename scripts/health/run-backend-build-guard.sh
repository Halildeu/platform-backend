#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." &> /dev/null && pwd)
REPO_ROOT=$(cd -- "$ROOT_DIR/.." &> /dev/null && pwd)
LOG_DIR="$ROOT_DIR/logs"
LOG_ARCHIVE_DIR="$LOG_DIR/archive"
STATE_DIR="$REPO_ROOT/.cache/build_guard"
SESSION_ID="${BACKEND_BUILD_SESSION_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
BACKEND_BUILD_LABEL="${BACKEND_BUILD_LABEL:-compile}"
BACKEND_BUILD_REPORT="${BACKEND_BUILD_REPORT:-$REPO_ROOT/.cache/reports/backend_build_guard.v1.json}"
BACKEND_BUILD_STRICT_WARNINGS="${BACKEND_BUILD_STRICT_WARNINGS:-1}"
CHECKER_SCRIPT="$ROOT_DIR/scripts/health/check-backend-build-guard.py"

declare -a MVN_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --label)
      BACKEND_BUILD_LABEL="${2:-compile}"
      shift 2
      ;;
    --report)
      BACKEND_BUILD_REPORT="${2:-$BACKEND_BUILD_REPORT}"
      shift 2
      ;;
    --)
      shift
      while [[ $# -gt 0 ]]; do
        MVN_ARGS+=("$1")
        shift
      done
      ;;
    *)
      MVN_ARGS+=("$1")
      shift
      ;;
  esac
done

if [[ ${#MVN_ARGS[@]} -eq 0 ]]; then
  MVN_ARGS=(-q -DskipTests compile)
fi

mkdir -p "$LOG_DIR" "$LOG_ARCHIVE_DIR" "$STATE_DIR"
LOG_PATH="$LOG_DIR/build-${BACKEND_BUILD_LABEL}.log"
if [[ -f "$LOG_PATH" ]]; then
  mv "$LOG_PATH" "$LOG_ARCHIVE_DIR/build-${BACKEND_BUILD_LABEL}.${SESSION_ID}.log"
fi

echo "[run-backend-build-guard] ./mvnw ${MVN_ARGS[*]} (cwd=$ROOT_DIR)"
set +e
(
  cd "$ROOT_DIR"
  ./mvnw "${MVN_ARGS[@]}"
) 2>&1 | tee "$LOG_PATH"
mvn_rc=${PIPESTATUS[0]}
set -e

guard_args=(
  "$CHECKER_SCRIPT"
  --log "$LOG_PATH"
  --report "$BACKEND_BUILD_REPORT"
  --label "$BACKEND_BUILD_LABEL"
  --command "./mvnw ${MVN_ARGS[*]}"
)
if [[ "$BACKEND_BUILD_STRICT_WARNINGS" == "1" ]]; then
  guard_args+=(--strict-warnings)
fi

set +e
python3 "${guard_args[@]}"
guard_rc=$?
set -e

if [[ "$mvn_rc" -ne 0 ]]; then
  exit "$mvn_rc"
fi
if [[ "$guard_rc" -ne 0 ]]; then
  exit "$guard_rc"
fi

echo "[ok] Backend build guard report: $BACKEND_BUILD_REPORT"
