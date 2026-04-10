#!/bin/sh
set -eu

VAULT_ADDR="${VAULT_ADDR:-http://vault:8200}"
KEYS_DIR="/vault-keys"
POLL_INTERVAL=5
export VAULT_ADDR

# vault status exit codes: 0=unsealed, 1=error, 2=sealed
check_sealed() {
  vault status -address="${VAULT_ADDR}" >/dev/null 2>&1
  rc=$?
  if [ "$rc" -eq 0 ]; then return 1; fi  # unsealed → not sealed
  if [ "$rc" -eq 2 ]; then return 0; fi  # sealed → is sealed
  return 1  # error → treat as not sealed (will retry)
}

do_unseal() {
  for key_file in "${KEYS_DIR}"/vault-unseal-key-*; do
    [ -f "$key_file" ] || continue
    vault operator unseal -address="${VAULT_ADDR}" "$(cat "$key_file" | tr -d '[:space:]')" >/dev/null 2>&1 || true
  done
}

# Wait for Vault API (up to 120s)
echo "[vault-unseal] Waiting for Vault at ${VAULT_ADDR}..."
waited=0
while [ "$waited" -lt 120 ]; do
  vault status -address="${VAULT_ADDR}" >/dev/null 2>&1
  rc=$?
  if [ "$rc" -eq 0 ] || [ "$rc" -eq 2 ]; then
    echo "[vault-unseal] Vault reachable after ${waited}s (status=$rc)"
    break
  fi
  waited=$((waited + 2))
  sleep 2
done

if [ "$waited" -ge 120 ]; then
  echo "[vault-unseal] FATAL: Vault unreachable after 120s" >&2
  exit 1
fi

# Unseal if needed (retry up to 10x)
if check_sealed; then
  echo "[vault-unseal] Vault is sealed. Unsealing..."
  attempt=0
  while [ "$attempt" -lt 10 ] && check_sealed; do
    attempt=$((attempt + 1))
    echo "[vault-unseal] Unseal attempt ${attempt}/10..."
    do_unseal
    sleep 3
  done

  if check_sealed; then
    echo "[vault-unseal] WARNING: still sealed after 10 attempts"
  else
    echo "[vault-unseal] Unsealed successfully on attempt ${attempt}"
  fi
else
  echo "[vault-unseal] Vault already unsealed"
fi

# Watch loop — re-unseal if Vault restarts
echo "[vault-unseal] Entering watch loop (poll every ${POLL_INTERVAL}s)..."
while true; do
  sleep "${POLL_INTERVAL}"
  if check_sealed; then
    echo "[vault-unseal] Vault sealed! Re-unsealing..."
    do_unseal
    if ! check_sealed; then
      echo "[vault-unseal] Re-unseal successful"
    fi
  fi
done
