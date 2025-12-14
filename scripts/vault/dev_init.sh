#!/usr/bin/env bash
set -euo pipefail
set +x

# Dev-only: initialize persistent Vault (raft) inside docker compose and store init output locally.
# Not: Bu script secret değerlerini loglamaz.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${BACKEND_ROOT}/docker-compose.yml"

STATE_DIR="${BACKEND_ROOT}/.vault-dev"
INIT_FILE="${STATE_DIR}/vault-init.json"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[vault-dev] HATA: '$1' komutu bulunamadı."
    exit 1
  fi
}

require_cmd docker
require_cmd jq

mkdir -p "${STATE_DIR}"
chmod 700 "${STATE_DIR}" 2>/dev/null || true

umask 077

docker compose -f "${COMPOSE_FILE}" up -d vault

status_json="$(docker compose -f "${COMPOSE_FILE}" exec -T vault vault status -address=http://127.0.0.1:8200 -format=json 2>/dev/null || true)"
initialized="$(jq -r '.initialized' <<<"${status_json}" 2>/dev/null || true)"

if [ -f "${INIT_FILE}" ]; then
  echo "[vault-dev] init dosyası zaten var: ${INIT_FILE}"
  exit 0
fi

if [ "${initialized}" = "true" ]; then
  echo "[vault-dev] HATA: Vault zaten initialized, ancak init dosyası yok: ${INIT_FILE}"
  echo "[vault-dev] Çözüm: vault-data volume'u sıfırlayın veya init dosyasını geri yükleyin."
  exit 1
fi

tmp_file="$(mktemp)"
cleanup() { rm -f "${tmp_file}" 2>/dev/null || true; }
trap cleanup EXIT

docker compose -f "${COMPOSE_FILE}" exec -T vault vault operator init \
  -address=http://127.0.0.1:8200 \
  -format=json \
  -key-shares=1 \
  -key-threshold=1 \
  > "${tmp_file}"

mv "${tmp_file}" "${INIT_FILE}"
chmod 600 "${INIT_FILE}" 2>/dev/null || true

echo "[vault-dev] OK: init tamamlandı. Çıktı dosyası: ${INIT_FILE}"
echo "[vault-dev] Sonraki adımlar:"
echo "  bash scripts/vault/dev_unseal.sh"
echo "  bash scripts/vault/dev_enable_kv.sh"
echo "[vault-dev] Not: token/anahtar değerlerini chat'e yapıştırmayın."
