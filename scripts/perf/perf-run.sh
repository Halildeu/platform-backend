#!/usr/bin/env bash
set -euo pipefail

# Simple wrapper to run k6 smoke test in Docker
# Requirements: Docker, running env (BASE_URL and TOKEN if endpoints require auth)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

BASE_URL=${BASE_URL:-http://localhost:8080}
TOKEN=${TOKEN:-}
RPS=${RPS:-50}
DURATION=${DURATION:-1m}
K6_IMAGE=${K6_IMAGE:-grafana/k6:0.48.0}

echo "Running k6 smoke: BASE_URL=$BASE_URL RPS=$RPS DURATION=$DURATION"

docker run --rm \
  -e BASE_URL="$BASE_URL" \
  -e TOKEN="$TOKEN" \
  -e RPS="$RPS" \
  -e DURATION="$DURATION" \
  -v "$REPO_ROOT/scripts/perf:/scripts" \
  -w /scripts \
  "$K6_IMAGE" run k6-smoke.js

