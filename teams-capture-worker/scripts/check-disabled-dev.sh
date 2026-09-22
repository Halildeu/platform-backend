#!/usr/bin/env bash
# Personal loopback smoke only. No credentials, Graph calls or shared service changes.
set -euo pipefail
if [[ $# != 2 || ! $2 =~ ^[0-9]{4,5}$ ]] || (( 10#$2 < 1024 || 10#$2 > 65535 )); then
  echo 'Usage: check-disabled-dev.sh /absolute/path/to/TeamsCapture.Worker UNUSED_PORT' >&2
  exit 2
fi
binary=$1
port=$2
[[ $binary == /* && -x $binary ]] || { echo 'An absolute executable path is required.' >&2; exit 2; }
for tool in ss curl python3 mktemp chmod env; do
  command -v "$tool" >/dev/null || { echo 'A required smoke tool is unavailable.' >&2; exit 2; }
done
if ! listeners=$(ss -H -ltn "sport = :$port"); then
  echo 'Port inspection failed; no process was started.' >&2
  exit 2
fi
if [[ -n $listeners ]]; then
  echo 'Port is already occupied; no service was changed.' >&2
  exit 2
fi
evidence=$(mktemp -d /tmp/teams-disabled-smoke.XXXXXX)
chmod 700 "$evidence"
pid=''
cleanup() {
  if [[ -n $pid ]]; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
}
trap cleanup EXIT
cd "$evidence"
env -i PATH=/usr/bin:/bin ASPNETCORE_ENVIRONMENT=Production TeamsCapture__Enabled=false \
  "$binary" --urls "http://127.0.0.1:$port" > worker.log 2>&1 &
pid=$!
base="http://127.0.0.1:$port"
for attempt in {1..30}; do
  kill -0 "$pid"
  if curl --disable --noproxy '*' -fsS --max-time 1 "$base/health" > health.json 2>/dev/null; then break; fi
  sleep 0.5
done
kill -0 "$pid"
python3 - <<'PY'
import json
with open('health.json') as source:
    health = json.load(source)
assert health['capture'] == 'disabled-until-tenant-registration', health
assert health['liveAudio'] is False, health
PY
check() {
  local name=$1 expected=$2
  shift 2
  kill -0 "$pid"
  local actual
  actual=$(curl --disable --noproxy '*' --silent --show-error --max-time 5 --output "$name.json" --write-out '%{http_code}' "$@")
  printf '%s expected=%s actual=%s\n' "$name" "$expected" "$actual"
  [[ $actual == "$expected" ]]
}
check readiness 401 "$base/api/teams/readiness"
check callback 401 -X POST -H 'Content-Type: application/json' --data '{"value":[]}' "$base/api/teams/callback"
check join-disabled 503 -X POST -H 'Content-Type: application/json' \
  --data '{"calendarEventId":"disabled-smoke","threadId":"synthetic","messageId":"0","organizerUserId":"11111111-1111-1111-1111-111111111111","correlationId":"disabled-smoke"}' \
  "$base/api/teams/meetings/22222222-2222-2222-2222-222222222222/join"
kill -0 "$pid"
cleanup
pid=''
printf 'PASS: private disabled smoke; process stopped; evidence=%s\n' "$evidence"
