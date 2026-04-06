#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ENV=prod "${SCRIPT_DIR}/check-backend-deploy-stage.sh" "$@"
