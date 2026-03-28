#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." &> /dev/null && pwd)
REPO_ROOT=$(cd -- "$ROOT_DIR/.." &> /dev/null && pwd)
COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"
STATE_DIR="$REPO_ROOT/.cache/runtime_guard"
LOG_ROOT="$STATE_DIR/compose_logs"
SESSION_ID="${COMPOSE_RUNTIME_SESSION_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
SESSION_CREATED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
SESSION_TSV="$STATE_DIR/backend_compose_session.${SESSION_ID}.tsv"
SESSION_FILE="${COMPOSE_RUNTIME_SESSION_FILE:-$STATE_DIR/backend_compose_session.v1.json}"
COMPOSE_LOG_DIR="$LOG_ROOT/$SESSION_ID"
COMPOSE_RUNTIME_SERVICES="${COMPOSE_RUNTIME_SERVICES:-all}"
COMPOSE_RUNTIME_SERVICES="${COMPOSE_RUNTIME_SERVICES// /}"
COMPOSE_RUNTIME_BUILD="${COMPOSE_RUNTIME_BUILD:-0}"
COMPOSE_RUNTIME_POSTCHECK="${COMPOSE_RUNTIME_POSTCHECK:-1}"
COMPOSE_RUNTIME_STRICT_WARNINGS="${COMPOSE_RUNTIME_STRICT_WARNINGS:-0}"
COMPOSE_RUNTIME_REPORT="${COMPOSE_RUNTIME_REPORT:-$REPO_ROOT/.cache/reports/backend_compose_runtime_guard.v1.json}"
COMPOSE_RUNTIME_PS_REPORT="${COMPOSE_RUNTIME_PS_REPORT:-$REPO_ROOT/.cache/reports/backend_compose_ps.v1.json}"
COMPOSE_RUNTIME_WAIT_SECONDS="${COMPOSE_RUNTIME_WAIT_SECONDS:-180}"
COMPOSE_RUNTIME_POLL_INTERVAL="${COMPOSE_RUNTIME_POLL_INTERVAL:-3}"
STARTUP_GUARD_SCRIPT="$ROOT_DIR/scripts/health/check-compose-runtime-guard.py"
SESSION_ENV_JSON="$(
  python3 <<'PY'
import json
import os

keys = [
    "COMPOSE_RUNTIME_SERVICES",
    "COMPOSE_RUNTIME_BUILD",
    "COMPOSE_RUNTIME_POSTCHECK",
    "COMPOSE_RUNTIME_STRICT_WARNINGS",
]
payload = {}
for key in keys:
    value = os.environ.get(key)
    if isinstance(value, str) and value != "":
        payload[key] = value
print(json.dumps(payload, ensure_ascii=False, sort_keys=True))
PY
)"

ALL_SERVICES=(
  discovery-server
  postgres-db
  user-service
  auth-service
  variant-service
  core-data-service
  api-gateway
  keycloak
  vault
  vault-unseal
  permission-service
  observability-prometheus
  observability-grafana
)

mkdir -p "$STATE_DIR" "$COMPOSE_LOG_DIR"
: > "$SESSION_TSV"

service_selected() {
  local name="$1"
  if [[ -z "$COMPOSE_RUNTIME_SERVICES" || "$COMPOSE_RUNTIME_SERVICES" == "all" ]]; then
    return 0
  fi

  case ",$COMPOSE_RUNTIME_SERVICES," in
    *,"$name",*)
      return 0
      ;;
  esac
  return 1
}

record_session_line() {
  local name="$1"; shift
  local status="$1"; shift
  local log_path="$1"; shift
  printf '%s\t%s\t%s\n' "$name" "$status" "$log_path" >> "$SESSION_TSV"
}

write_session_json() {
  python3 - "$SESSION_TSV" "$SESSION_FILE" "$SESSION_ID" "$SESSION_CREATED_AT" "$COMPOSE_RUNTIME_SERVICES" "$COMPOSE_FILE" "$COMPOSE_LOG_DIR" "$COMPOSE_RUNTIME_PS_REPORT" "$SESSION_ENV_JSON" <<'PY'
import json
import sys
from pathlib import Path

tsv_path = Path(sys.argv[1])
out_path = Path(sys.argv[2])
session_id = sys.argv[3]
created_at = sys.argv[4]
selected_filter = sys.argv[5] or "all"
compose_file = Path(sys.argv[6]).resolve()
compose_log_dir = Path(sys.argv[7]).resolve()
compose_ps_report = Path(sys.argv[8]).resolve()
environment = json.loads(sys.argv[9])

services = []
for raw_line in tsv_path.read_text(encoding="utf-8").splitlines():
    if not raw_line.strip():
        continue
    name, status, log_path = raw_line.split("\t")
    services.append(
        {
            "name": name,
            "status": status,
            "log_path": log_path,
        }
    )

payload = {
    "version": "v1",
    "kind": "backend-compose-session",
    "session_id": session_id,
    "created_at": created_at,
    "selected_filter": selected_filter,
    "environment": environment,
    "compose_file": str(compose_file),
    "compose_log_dir": str(compose_log_dir),
    "compose_ps_report": str(compose_ps_report),
    "services": services,
}
out_path.parent.mkdir(parents=True, exist_ok=True)
out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

selected_services=()
for service_name in "${ALL_SERVICES[@]}"; do
  log_path="$COMPOSE_LOG_DIR/${service_name}.log"
  if service_selected "$service_name"; then
    selected_services+=("$service_name")
    record_session_line "$service_name" "started" "$log_path"
  else
    record_session_line "$service_name" "filtered" "$log_path"
  fi
done

write_session_json

if ! command -v docker >/dev/null 2>&1; then
  echo "[error] docker bulunamadi; compose full-stack baslatilamadi" >&2
  exit 1
fi

compose_args=(compose -f "$COMPOSE_FILE" up -d)
if [[ "$COMPOSE_RUNTIME_BUILD" == "1" ]]; then
  compose_args+=(--build)
fi
if [[ "${#selected_services[@]}" -gt 0 ]]; then
  compose_args+=("${selected_services[@]}")
fi

echo "[compose] Starting services: ${COMPOSE_RUNTIME_SERVICES:-all}"
compose_up_rc=0
if ! docker "${compose_args[@]}"; then
  compose_up_rc=$?
  echo "[warn] docker compose up exit code: $compose_up_rc" >&2
fi

echo "[ok] Compose startup session file: $SESSION_FILE"

if [[ "$COMPOSE_RUNTIME_POSTCHECK" == "1" && -f "$STARTUP_GUARD_SCRIPT" ]]; then
  guard_args=(
    "$STARTUP_GUARD_SCRIPT"
    --session-file "$SESSION_FILE"
    --report "$COMPOSE_RUNTIME_REPORT"
    --wait-seconds "$COMPOSE_RUNTIME_WAIT_SECONDS"
    --poll-interval "$COMPOSE_RUNTIME_POLL_INTERVAL"
  )
  if [[ "$COMPOSE_RUNTIME_STRICT_WARNINGS" == "1" ]]; then
    guard_args+=(--strict-warnings)
  fi
  python3 "${guard_args[@]}"
  echo "[ok] Compose runtime guard report: $COMPOSE_RUNTIME_REPORT"
else
  echo "[info] Compose runtime guard skipped (COMPOSE_RUNTIME_POSTCHECK=$COMPOSE_RUNTIME_POSTCHECK)"
fi

if [[ "$compose_up_rc" -ne 0 ]]; then
  exit "$compose_up_rc"
fi
