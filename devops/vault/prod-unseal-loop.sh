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

# Wait for Vault API to be reachable (sealed or unsealed).
# vault status exit codes: 0=unsealed, 1=error/unreachable, 2=sealed
# Both 0 and 2 mean Vault is running — proceed to unseal check.
while true; do
  vault status -address="${VAULT_ADDR}" >/dev/null 2>&1
  rc=$?
  if [ "$rc" -eq 0 ] || [ "$rc" -eq 2 ]; then
    break
  fi
  sleep 2
done

# Check if already unsealed
if vault status -address="${VAULT_ADDR}" -format=json 2>/dev/null | grep -q '"sealed":false'; then
  echo "[vault-unseal] Vault already unsealed."
else
  echo "[vault-unseal] Vault is sealed. Attempting unseal..."

  # Read and apply unseal keys with retry
  attempt=0
  max_attempts=5
  while [ "$attempt" -lt "$max_attempts" ]; do
    attempt=$((attempt + 1))
    echo "[vault-unseal] Unseal attempt ${attempt}/${max_attempts}..."

    for key_file in "${KEYS_DIR}"/vault-unseal-key-*; do
      if [ -f "$key_file" ]; then
        key=$(cat "$key_file" | tr -d '[:space:]')
        vault operator unseal -address="${VAULT_ADDR}" "$key" 2>/dev/null || true
      fi
    done

    if vault status -address="${VAULT_ADDR}" -format=json 2>/dev/null | grep -q '"sealed":false'; then
      echo "[vault-unseal] Vault successfully unsealed on attempt ${attempt}."
      break
    fi

    if [ "$attempt" -lt "$max_attempts" ]; then
      echo "[vault-unseal] Still sealed, retrying in 3s..."
      sleep 3
    fi
  done

  if vault status -address="${VAULT_ADDR}" -format=json 2>/dev/null | grep -q '"sealed":true'; then
    echo "[vault-unseal] WARNING: Vault still sealed after ${max_attempts} attempts."
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
