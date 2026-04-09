#!/bin/sh
set -eu

# Takes periodic Raft snapshots for disaster recovery.
# Runs as a long-lived sidecar, taking a snapshot every SNAPSHOT_INTERVAL seconds.

VAULT_ADDR="${VAULT_ADDR:-https://vault:8200}"
export VAULT_ADDR
export VAULT_CACERT="${VAULT_CACERT:-/vault/tls/tls.crt}"
SNAPSHOT_DIR="${SNAPSHOT_DIR:-/vault/snapshots}"
SNAPSHOT_INTERVAL="${SNAPSHOT_INTERVAL:-86400}"  # 24 hours
SNAPSHOT_RETAIN="${SNAPSHOT_RETAIN:-7}"

mkdir -p "${SNAPSHOT_DIR}"

# Wait for Vault to be unsealed
MAX_WAIT=120
waited=0
echo "[vault-snapshot] waiting for Vault..."
while [ "${waited}" -lt "${MAX_WAIT}" ]; do
  if vault status >/dev/null 2>&1; then
    break
  fi
  sleep 5
  waited=$((waited + 5))
done

if [ "${waited}" -ge "${MAX_WAIT}" ]; then
  echo "[vault-snapshot] timeout waiting for Vault" >&2
  exit 1
fi

# Login with token from file if available
if [ -f /vault-keys/root-token ]; then
  VAULT_TOKEN="$(cat /vault-keys/root-token | tr -d '[:space:]')"
  export VAULT_TOKEN
fi

echo "[vault-snapshot] running (interval=${SNAPSHOT_INTERVAL}s, retain=${SNAPSHOT_RETAIN})"

while true; do
  ts="$(date -u +%Y%m%d-%H%M%S)"
  snapshot_file="${SNAPSHOT_DIR}/raft-snapshot-${ts}.snap"

  if vault operator raft snapshot save "${snapshot_file}" 2>/dev/null; then
    echo "[vault-snapshot] saved ${snapshot_file}"

    # Rotate: keep only N most recent snapshots
    count="$(ls -1t "${SNAPSHOT_DIR}"/raft-snapshot-*.snap 2>/dev/null | wc -l)"
    if [ "${count}" -gt "${SNAPSHOT_RETAIN}" ]; then
      ls -1t "${SNAPSHOT_DIR}"/raft-snapshot-*.snap | tail -n +"$((SNAPSHOT_RETAIN + 1))" | xargs rm -f
      echo "[vault-snapshot] rotated old snapshots (kept ${SNAPSHOT_RETAIN})"
    fi
  else
    echo "[vault-snapshot] snapshot failed" >&2
  fi

  sleep "${SNAPSHOT_INTERVAL}"
done
