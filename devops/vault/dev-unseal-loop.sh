#!/usr/bin/env sh
set -eu

# Dev-only: keep Vault unsealed after restarts (Shamir seal).
#
# Notes:
# - Reads unseal key from bind-mounted backend/.vault-dev (NOT committed).
# - Never prints secret values.
#
# Required (docker-compose mounts):
#   /vault-dev/vault-unseal-key   (preferred, plain text)
#   or /vault-dev/vault-init.json (fallback, init output JSON)
#
# Environment:
#   VAULT_ADDR (default: http://vault:8200)

VAULT_ADDR="${VAULT_ADDR:-http://vault:8200}"
STATE_DIR="${STATE_DIR:-/vault-dev}"
UNSEAL_KEY_FILE="${UNSEAL_KEY_FILE:-${STATE_DIR}/vault-unseal-key}"
INIT_FILE="${INIT_FILE:-${STATE_DIR}/vault-init.json}"

log() {
  echo "[vault-unseal] $*"
}

read_unseal_key() {
  if [ -s "${UNSEAL_KEY_FILE}" ]; then
    cat "${UNSEAL_KEY_FILE}"
    return 0
  fi

  if [ -s "${INIT_FILE}" ]; then
    # Parse init JSON without jq (Vault image is minimal).
    # Extract first element of "unseal_keys_b64": ["..."].
    tr -d '\n' < "${INIT_FILE}" \
      | sed -n 's/.*"unseal_keys_b64"[[:space:]]*:[[:space:]]*\\[[[:space:]]*"\\([^"]*\\)".*/\\1/p'
    return 0
  fi

  return 1
}

ensure_unsealed() {
  status_json="$(vault status -address="${VAULT_ADDR}" -format=json 2>/dev/null || true)"
  if [ -z "${status_json}" ]; then
    return 0
  fi

  if echo "${status_json}" | grep -Eq '"sealed":[[:space:]]*false'; then
    return 0
  fi

  if ! echo "${status_json}" | grep -Eq '"sealed":[[:space:]]*true'; then
    return 0
  fi

  key="$(read_unseal_key 2>/dev/null || true)"
  if [ -z "${key}" ]; then
    if [ "${missing_key_warned}" != "1" ]; then
      log "SKIP: unseal key not found. Run: bash backend/scripts/vault/dev_init.sh"
      missing_key_warned="1"
    fi
    return 0
  fi
  missing_key_warned="0"

  if vault operator unseal -address="${VAULT_ADDR}" "${key}" >/dev/null 2>&1; then
    log "OK: unsealed"
  else
    log "WARN: unseal attempt failed (will retry)"
  fi
}

log "Starting (VAULT_ADDR=${VAULT_ADDR})"

missing_key_warned="0"

while true; do
  ensure_unsealed || true
  sleep 2
done
