#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." &> /dev/null && pwd)
REPO_ROOT=$(cd -- "$ROOT_DIR/.." &> /dev/null && pwd)
REPORT_PATH="${BACKEND_RUNTIME_GUARD_REPORT:-$REPO_ROOT/.cache/reports/backend_runtime_guard.v1.json}"
STATE_DIR="$REPO_ROOT/.cache/runtime_guard"
PREVIOUS_SESSION_PATH="$STATE_DIR/backend_runtime_lane.previous_session.v1.json"
RESTORE_REPORT_PATH="$REPO_ROOT/.cache/reports/backend_runtime_lane.restore_guard.v1.json"
BACKEND_RUNTIME_LANE_RESTORE_PREVIOUS="${BACKEND_RUNTIME_LANE_RESTORE_PREVIOUS:-1}"
STATUS_REPORT_PATH="${BACKEND_RUNTIME_LANE_STATUS_REPORT:-$REPO_ROOT/.cache/reports/backend_runtime_lane.status.v1.json}"
PREVIOUS_SESSION_CAPTURED=0
RESTORE_ATTEMPTED=0
RESTORE_SUCCEEDED=0
SHUTDOWN_MODE="pending"

capture_previous_session() {
  python3 - "$REPO_ROOT/.cache/runtime_guard/backend_start_session.v1.json" "$REPORT_PATH" "$PREVIOUS_SESSION_PATH" <<'PY'
import json
import socket
import sys
from pathlib import Path

session_path = Path(sys.argv[1])
report_path = Path(sys.argv[2])
out_path = Path(sys.argv[3])

if not session_path.exists() or not report_path.exists():
    raise SystemExit(1)

session = json.loads(session_path.read_text(encoding="utf-8"))
report = json.loads(report_path.read_text(encoding="utf-8"))

if session.get("kind") != "backend-start-session":
    raise SystemExit(1)
if report.get("status") != "OK":
    raise SystemExit(1)
if report.get("summary", {}).get("failed_services"):
    raise SystemExit(1)

for service in session.get("services", []):
    if service.get("status") not in {"started", "already_running"}:
        continue
    port = int(service.get("port", 0))
    if port <= 0:
        raise SystemExit(1)
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(1.0)
        try:
            sock.connect(("127.0.0.1", port))
        except OSError:
            raise SystemExit(1)

out_path.parent.mkdir(parents=True, exist_ok=True)
out_path.write_text(json.dumps(session, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

restore_previous_session() {
  if [[ "$BACKEND_RUNTIME_LANE_RESTORE_PREVIOUS" != "1" || ! -f "$PREVIOUS_SESSION_PATH" ]]; then
    return 1
  fi

  RESTORE_ATTEMPTED=1
  local selected_filter
  local auto_start_infra
  selected_filter="$(python3 - "$PREVIOUS_SESSION_PATH" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
print(payload.get("selected_filter", "all"))
PY
)"
  auto_start_infra="$(python3 - "$PREVIOUS_SESSION_PATH" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
print("1" if payload.get("auto_start_infra", True) else "0")
PY
)"

  AUTO_START_INFRA="$auto_start_infra" \
  RUN_SERVICES_POSTCHECK=1 \
  RUN_SERVICES_STRICT_WARNINGS=0 \
  RUN_SERVICES_FILTER="$selected_filter" \
  STARTUP_GUARD_REPORT="$RESTORE_REPORT_PATH" \
  "$ROOT_DIR/scripts/run-services.sh"
  RESTORE_SUCCEEDED=1
  SHUTDOWN_MODE="restored_previous"
}

write_status_report() {
  local lane_exit_code="$1"
  python3 - "$STATUS_REPORT_PATH" "$lane_exit_code" "$PREVIOUS_SESSION_CAPTURED" "$RESTORE_ATTEMPTED" "$RESTORE_SUCCEEDED" "$SHUTDOWN_MODE" "$REPORT_PATH" "$RESTORE_REPORT_PATH" "$PREVIOUS_SESSION_PATH" "$REPO_ROOT/.cache/runtime_guard/backend_start_session.v1.json" <<'PY'
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

def load_json(path_str):
    path = Path(path_str)
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return None

out_path = Path(sys.argv[1])
lane_exit_code = int(sys.argv[2])
previous_session_captured = sys.argv[3] == "1"
restore_attempted = sys.argv[4] == "1"
restore_succeeded = sys.argv[5] == "1"
shutdown_mode = sys.argv[6]
runtime_report_path = sys.argv[7]
restore_report_path = sys.argv[8]
previous_session_path = sys.argv[9]
current_session_path = sys.argv[10]

payload = {
    "version": "v1",
    "kind": "backend-runtime-lane-status-report",
    "generated_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    "status": "OK" if lane_exit_code == 0 else "FAILED",
    "startup_mode": "fresh_start",
    "shutdown_mode": shutdown_mode,
    "lane_exit_code": lane_exit_code,
    "previous_session_captured": previous_session_captured,
    "restore_attempted": restore_attempted,
    "restore_succeeded": restore_succeeded,
    "paths": {
        "status_report": str(out_path),
        "runtime_report": runtime_report_path,
        "restore_report": restore_report_path,
        "previous_session_snapshot": previous_session_path,
        "current_session_file": current_session_path,
    },
    "previous_session": load_json(previous_session_path),
    "current_session": load_json(current_session_path),
    "runtime_report": load_json(runtime_report_path),
    "restore_report": load_json(restore_report_path),
}
out_path.parent.mkdir(parents=True, exist_ok=True)
out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

cleanup() {
  local lane_exit_code=$?
  if restore_previous_session; then
    :
  else
    SHUTDOWN_MODE="stopped_only"
    STOP_INFRA=1 "$ROOT_DIR/scripts/stop-services.sh" >/dev/null 2>&1 || true
  fi
  write_status_report "$lane_exit_code"
  trap - EXIT
  exit "$lane_exit_code"
}

trap cleanup EXIT

mkdir -p "$STATE_DIR"
rm -f "$PREVIOUS_SESSION_PATH"
if capture_previous_session >/dev/null 2>&1; then
  PREVIOUS_SESSION_CAPTURED=1
fi
STOP_INFRA=1 "$ROOT_DIR/scripts/stop-services.sh" >/dev/null 2>&1 || true
AUTO_START_INFRA=1 \
RUN_SERVICES_POSTCHECK=1 \
RUN_SERVICES_STRICT_WARNINGS=1 \
RUN_SERVICES_FILTER="${RUN_SERVICES_FILTER:-all}" \
STARTUP_GUARD_REPORT="$REPORT_PATH" \
"$ROOT_DIR/scripts/run-services.sh"

echo "[ok] backend runtime lane report: $REPORT_PATH"
