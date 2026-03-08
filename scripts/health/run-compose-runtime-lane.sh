#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." &> /dev/null && pwd)
REPO_ROOT=$(cd -- "$ROOT_DIR/.." &> /dev/null && pwd)
REPORT_PATH="${BACKEND_COMPOSE_RUNTIME_GUARD_REPORT:-$REPO_ROOT/.cache/reports/backend_compose_runtime_guard.v1.json}"

cleanup() {
  docker compose -f "$ROOT_DIR/docker-compose.yml" down --remove-orphans >/dev/null 2>&1 || true
}

trap cleanup EXIT

docker compose -f "$ROOT_DIR/docker-compose.yml" down --remove-orphans >/dev/null 2>&1 || true
COMPOSE_RUNTIME_POSTCHECK=1 \
COMPOSE_RUNTIME_STRICT_WARNINGS=1 \
COMPOSE_RUNTIME_BUILD="${COMPOSE_RUNTIME_BUILD:-1}" \
COMPOSE_RUNTIME_SERVICES="${COMPOSE_RUNTIME_SERVICES:-all}" \
COMPOSE_RUNTIME_REPORT="$REPORT_PATH" \
"$ROOT_DIR/scripts/run-compose-stack.sh"

echo "[ok] backend compose runtime lane report: $REPORT_PATH"
