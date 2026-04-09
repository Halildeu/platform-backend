#!/bin/sh
# Auto-unseal loop for production Vault (Raft storage).
# Reads Shamir unseal keys from mounted /vault-keys directory.
# Runs as a sidecar container — polls until Vault is unsealed.

set -eu

VAULT_ADDR="${VAULT_ADDR:-http://vault:8200}"
KEYS_DIR="/vault-keys"
POLL_INTERVAL=5

export VAULT_ADDR

echo "[vault-unseal] Waiting for Vault at ${VAULT_ADDR}..."

# Wait for Vault to be reachable
until vault status -address="${VAULT_ADDR}" 2>/dev/null; do
  sleep 2
done

# Check if already unsealed
if vault status -address="${VAULT_ADDR}" -format=json 2>/dev/null | grep -q '"sealed":false'; then
  echo "[vault-unseal] Vault already unsealed."
else
  echo "[vault-unseal] Vault is sealed. Attempting unseal..."

  # Read and apply unseal keys
  for key_file in "${KEYS_DIR}"/vault-unseal-key-*; do
    if [ -f "$key_file" ]; then
      key=$(cat "$key_file")
      echo "[vault-unseal] Applying key from $(basename "$key_file")..."
      vault operator unseal -address="${VAULT_ADDR}" "$key" 2>/dev/null || true
    fi
  done

  # Verify
  if vault status -address="${VAULT_ADDR}" -format=json 2>/dev/null | grep -q '"sealed":false'; then
    echo "[vault-unseal] Vault successfully unsealed."
  else
    echo "[vault-unseal] WARNING: Vault still sealed after applying all keys."
  fi
fi

# Keep running — re-unseal if Vault restarts
echo "[vault-unseal] Entering watch loop (poll every ${POLL_INTERVAL}s)..."
while true; do
  sleep "${POLL_INTERVAL}"
  if vault status -address="${VAULT_ADDR}" -format=json 2>/dev/null | grep -q '"sealed":true'; then
    echo "[vault-unseal] Vault sealed! Re-unsealing..."
    for key_file in "${KEYS_DIR}"/vault-unseal-key-*; do
      if [ -f "$key_file" ]; then
        vault operator unseal -address="${VAULT_ADDR}" "$(cat "$key_file")" 2>/dev/null || true
      fi
    done
  fi
done
