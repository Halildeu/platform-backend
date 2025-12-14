#!/usr/bin/env bash
set -euo pipefail

# Convenience wrapper for stage script with ENV=prod.
# Usage:
#   VAULT_ADDR=https://vault-prod.example.com \
#   VAULT_TOKEN=<token> \
#   SERVICE_CLIENT_USER_SERVICE_SECRET=<secret> \
#   ./scripts/vault/write-secrets-prod.sh

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ENV=prod "${SCRIPT_DIR}/write-secrets-stage.sh" "$@"
