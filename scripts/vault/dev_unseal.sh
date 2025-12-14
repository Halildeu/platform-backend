#!/usr/bin/env bash
set -euo pipefail
set +x

# Dev-only: unseal Vault using the locally stored init file.
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

if [ ! -f "${INIT_FILE}" ]; then
  echo "[vault-dev] HATA: init dosyası yok: ${INIT_FILE}"
  echo "[vault-dev] Önce çalıştırın: bash scripts/vault/dev_init.sh"
  exit 1
fi

docker compose -f "${COMPOSE_FILE}" up -d vault

status_json="$(docker compose -f "${COMPOSE_FILE}" exec -T vault vault status -address=http://127.0.0.1:8200 -format=json 2>/dev/null || true)"
sealed="$(jq -r '.sealed' <<<"${status_json}" 2>/dev/null || true)"

if [ "${sealed}" = "false" ]; then
  echo "[vault-dev] Vault zaten unsealed."
  exit 0
fi

if [ "${sealed}" != "true" ]; then
  echo "[vault-dev] HATA: Vault sealed durumu okunamadı (sealed=${sealed:-<empty>})."
  exit 1
fi

unseal_key="$(jq -r '.unseal_keys_b64[0] // empty' "${INIT_FILE}")"
if [ -z "${unseal_key}" ] || [ "${unseal_key}" = "null" ]; then
  echo "[vault-dev] HATA: init dosyasında unseal key bulunamadı: ${INIT_FILE}"
  exit 1
fi

# Not: tty olmadığı için key arg olarak geçilir; script value loglamaz.
docker compose -f "${COMPOSE_FILE}" exec -T vault vault operator unseal \
  -address=http://127.0.0.1:8200 \
  "${unseal_key}" \
  >/dev/null

echo "[vault-dev] OK: unseal tamamlandı."
