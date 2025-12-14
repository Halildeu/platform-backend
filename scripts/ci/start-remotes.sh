#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

cd "$ROOT_DIR"

echo "[ci:start-remotes] Launching minimal remotes for Cypress..."

npx concurrently --kill-others-on-fail --names "shell,reporting,users,access" \
  "npm start --prefix apps/mfe-shell" \
  "npm start --prefix apps/mfe-reporting" \
  "npm start --prefix apps/mfe-users" \
  "npm start --prefix apps/mfe-access"
