#!/bin/sh
set -eu

# Wait for Vault to be unsealed, then enable file audit device.
# Runs once at startup as an init sidecar.

VAULT_ADDR="${VAULT_ADDR:-https://vault:8200}"
export VAULT_ADDR
export VAULT_CACERT="${VAULT_CACERT:-/vault/tls/tls.crt}"

MAX_WAIT=120
waited=0

echo "[vault-audit] waiting for Vault to be unsealed..."
while [ "${waited}" -lt "${MAX_WAIT}" ]; do
  if vault status >/dev/null 2>&1; then
    break
  fi
  sleep 2
  waited=$((waited + 2))
done

if [ "${waited}" -ge "${MAX_WAIT}" ]; then
  echo "[vault-audit] timeout waiting for Vault" >&2
  exit 1
fi

# Login with token from file if available
if [ -f /vault-keys/root-token ]; then
  VAULT_TOKEN="$(cat /vault-keys/root-token | tr -d '[:space:]')"
  export VAULT_TOKEN
fi

# Check if audit device already enabled
if vault audit list 2>/dev/null | grep -q 'file/'; then
  echo "[vault-audit] file audit device already enabled"
  exit 0
fi

# Enable file audit device
vault audit enable file file_path=/vault/logs/audit.log 2>/dev/null && \
  echo "[vault-audit] enabled file audit at /vault/logs/audit.log" || \
  echo "[vault-audit] audit enable failed (may already exist)"
