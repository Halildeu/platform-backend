#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." &> /dev/null && pwd)
REPO_ROOT=$(cd -- "$ROOT_DIR/.." &> /dev/null && pwd)
LOG_DIR="$ROOT_DIR/logs"
LOG_ARCHIVE_DIR="$LOG_DIR/archive"
COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"
STATE_DIR="$REPO_ROOT/.cache/runtime_guard"
SESSION_ID="${RUN_SERVICES_SESSION_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
SESSION_CREATED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
SESSION_TSV="$STATE_DIR/backend_start_session.${SESSION_ID}.tsv"
SESSION_FILE="${RUN_SERVICES_SESSION_FILE:-$STATE_DIR/backend_start_session.v1.json}"
AUTO_START_INFRA="${AUTO_START_INFRA:-1}"
RUN_SERVICES_POSTCHECK="${RUN_SERVICES_POSTCHECK:-1}"
RUN_SERVICES_STRICT_WARNINGS="${RUN_SERVICES_STRICT_WARNINGS:-0}"
RUN_SERVICES_FILTER="${RUN_SERVICES_FILTER:-all}"
RUN_SERVICES_FILTER="${RUN_SERVICES_FILTER// /}"
STARTUP_GUARD_SCRIPT="$ROOT_DIR/scripts/health/check-runtime-guard.py"
STARTUP_GUARD_REPORT="${STARTUP_GUARD_REPORT:-$REPO_ROOT/.cache/reports/backend_runtime_guard.v1.json}"
STARTUP_GUARD_WAIT_SECONDS="${STARTUP_GUARD_WAIT_SECONDS:-90}"
STARTUP_GUARD_POLL_INTERVAL="${STARTUP_GUARD_POLL_INTERVAL:-2}"
JAVA_RUNTIME_HELPER="$ROOT_DIR/scripts/java-runtime.sh"

if [[ -f "$JAVA_RUNTIME_HELPER" ]]; then
  # shellcheck source=/dev/null
  . "$JAVA_RUNTIME_HELPER"
  codex_select_java_runtime "${JAVA_RUNTIME_TARGET:-21}" "run-services.sh"
fi

export VARIANT_DB_SCHEMA="${VARIANT_DB_SCHEMA:-variant_service}"
export CORE_DATA_DB_SCHEMA="${CORE_DATA_DB_SCHEMA:-core_data_service}"
export CORE_DATA_DB_URL="${CORE_DATA_DB_URL:-jdbc:postgresql://localhost:5432/users}"
mkdir -p "$LOG_DIR" "$LOG_ARCHIVE_DIR" "$STATE_DIR"
: > "$SESSION_TSV"

service_selected() {
  local name="$1"
  if [[ -z "$RUN_SERVICES_FILTER" || "$RUN_SERVICES_FILTER" == "all" ]]; then
    return 0
  fi

  case ",$RUN_SERVICES_FILTER," in
    *,"$name",*)
      return 0
      ;;
  esac
  return 1
}

record_session_line() {
  local name="$1"; shift
  local pom="$1"; shift
  local port="$1"; shift
  local status="$1"; shift
  local log="$1"; shift
  printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$port" "$status" "$pom" "$log" >> "$SESSION_TSV"
}

write_session_json() {
  python3 - "$SESSION_TSV" "$SESSION_FILE" "$SESSION_ID" "$SESSION_CREATED_AT" "$AUTO_START_INFRA" "$RUN_SERVICES_FILTER" <<'PY'
import json
import sys
from pathlib import Path

tsv_path = Path(sys.argv[1])
out_path = Path(sys.argv[2])
session_id = sys.argv[3]
created_at = sys.argv[4]
auto_start_infra = sys.argv[5] == "1"
selected_filter = sys.argv[6] or "all"

services = []
for raw_line in tsv_path.read_text(encoding="utf-8").splitlines():
    if not raw_line.strip():
        continue
    name, port, status, pom_path, log_path = raw_line.split("\t")
    services.append(
        {
            "name": name,
            "port": int(port),
            "status": status,
            "pom_path": pom_path,
            "log_path": log_path,
        }
    )

payload = {
    "version": "v1",
    "kind": "backend-start-session",
    "session_id": session_id,
    "created_at": created_at,
    "auto_start_infra": auto_start_infra,
    "selected_filter": selected_filter,
    "services": services,
}
out_path.parent.mkdir(parents=True, exist_ok=True)
out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

rotate_log() {
  local name="$1"; shift
  local log="$1"; shift
  if [[ -f "$log" ]]; then
    mv "$log" "$LOG_ARCHIVE_DIR/${name}.${SESSION_ID}.log"
  fi
  : > "$log"
}

wait_for_tcp() {
  local host="$1"; shift
  local port="$1"; shift
  local name="$1"; shift
  local max_attempts="${1:-45}"
  local sleep_s="${2:-2}"

  local attempt
  for ((attempt = 1; attempt <= max_attempts; attempt++)); do
    if python3 - "$host" "$port" <<'PY' >/dev/null 2>&1
import socket
import sys

host = sys.argv[1]
port = int(sys.argv[2])
with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
    sock.settimeout(1.0)
    sock.connect((host, port))
PY
    then
      echo "[infra] $name hazır (${host}:${port})"
      return 0
    fi
    sleep "$sleep_s"
  done

  echo "[warn] $name ${host}:${port} üzerinde beklenen sürede hazır olmadı" >&2
  return 1
}

wait_for_selected_port() {
  local name="$1"; shift
  local port="$1"; shift
  if ! service_selected "$name"; then
    return 0
  fi
  wait_for_tcp 127.0.0.1 "$port" "$name" "$@"
}

start_infra() {
  if [[ "$AUTO_START_INFRA" != "1" ]]; then
    echo "[infra] AUTO_START_INFRA=0, compose tabanli bagimliliklar atlandi"
    return 0
  fi

  if ! command -v docker >/dev/null 2>&1; then
    echo "[warn] docker bulunamadi; infra auto-start atlandi" >&2
    return 0
  fi

  echo "[infra] Starting docker compose dependencies: postgres-db keycloak vault vault-unseal"
  docker compose -f "$COMPOSE_FILE" up -d postgres-db keycloak vault vault-unseal

  wait_for_tcp 127.0.0.1 5432 "postgres-db" || true
  wait_for_tcp 127.0.0.1 8081 "keycloak" || true
  wait_for_tcp 127.0.0.1 8200 "vault" || true
}

run() {
  local name="$1"; shift
  local pom="$1"; shift
  local port="$1"; shift
  local log="$LOG_DIR/${name}.log"

  if ! service_selected "$name"; then
    echo "[skip] $name RUN_SERVICES_FILTER=$RUN_SERVICES_FILTER nedeniyle atlandi"
    record_session_line "$name" "$pom" "$port" "filtered" "$log"
    return 0
  fi

  echo "[run] $name (port $port) → $log"
  if lsof -iTCP:"$port" -sTCP:LISTEN -Pn &>/dev/null; then
    echo "[info] $name port $port already in use; startup skipped"
    record_session_line "$name" "$pom" "$port" "already_running" "$log"
  else
    rotate_log "$name" "$log"
    printf '[session] %s %s\n' "$SESSION_ID" "$SESSION_CREATED_AT" >> "$log"
    python3 - "$ROOT_DIR" "$pom" "$log" <<'PY'
import subprocess
import sys
from pathlib import Path

root_dir = Path(sys.argv[1])
pom_path = Path(sys.argv[2])
log_path = Path(sys.argv[3])

with log_path.open("ab", buffering=0) as log_file:
    subprocess.Popen(
        [str(root_dir / "mvnw"), "-q", "-f", str(pom_path), "spring-boot:run"],
        cwd=root_dir,
        stdin=subprocess.DEVNULL,
        stdout=log_file,
        stderr=log_file,
        start_new_session=True,
    )
PY
    record_session_line "$name" "$pom" "$port" "started" "$log"
  fi
}

run discovery-server    "$ROOT_DIR/discovery-server/pom.xml"    8761
wait_for_selected_port discovery-server 8761 || true
start_infra
run auth-service        "$ROOT_DIR/auth-service/pom.xml"        8088
run permission-service  "$ROOT_DIR/permission-service/pom.xml"  8084
run user-service        "$ROOT_DIR/user-service/pom.xml"        8089
run variant-service     "$ROOT_DIR/variant-service/pom.xml"     8091
run core-data-service   "$ROOT_DIR/core-data-service/pom.xml"   8092
wait_for_selected_port auth-service 8088 || true
wait_for_selected_port permission-service 8084 || true
wait_for_selected_port user-service 8089 || true
wait_for_selected_port variant-service 8091 || true
wait_for_selected_port core-data-service 8092 || true
run api-gateway         "$ROOT_DIR/api-gateway/pom.xml"         8080

write_session_json

echo "[ok] Services starting. Infra auto-start: postgres-db, keycloak, vault, vault-unseal"
echo "[ok] App start list: discovery-server, auth-service, permission-service, user-service, variant-service, core-data-service, api-gateway"
echo "[ok] Startup session file: $SESSION_FILE"
echo "[ok] Tail logs with: tail -f logs/*.log"

if [[ "$RUN_SERVICES_POSTCHECK" == "1" && -f "$STARTUP_GUARD_SCRIPT" ]]; then
  guard_args=(
    "$STARTUP_GUARD_SCRIPT"
    --session-file "$SESSION_FILE"
    --report "$STARTUP_GUARD_REPORT"
    --wait-seconds "$STARTUP_GUARD_WAIT_SECONDS"
    --poll-interval "$STARTUP_GUARD_POLL_INTERVAL"
  )
  if [[ "$RUN_SERVICES_STRICT_WARNINGS" == "1" ]]; then
    guard_args+=(--strict-warnings)
  fi
  python3 "${guard_args[@]}"
  echo "[ok] Runtime guard report: $STARTUP_GUARD_REPORT"
else
  echo "[info] Runtime guard skipped (RUN_SERVICES_POSTCHECK=$RUN_SERVICES_POSTCHECK)"
fi
