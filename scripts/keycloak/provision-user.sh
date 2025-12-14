#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

USER_SERVICE_URL="${USER_SERVICE_URL:-http://localhost:8089}"
SERVICE_TOKEN_URL="${SERVICE_TOKEN_URL:-http://localhost:8088/oauth2/token}"
SERVICE_CLIENT_ID="${SERVICE_CLIENT_ID:-user-service}"
SERVICE_CLIENT_SECRET="${SERVICE_CLIENT_SECRET:-}"
SERVICE_TOKEN_AUDIENCE="${SERVICE_TOKEN_AUDIENCE:-user-service}"
SERVICE_TOKEN_PERMISSIONS="${SERVICE_TOKEN_PERMISSIONS:-users:internal}"
DEFAULT_ROLE="${DEFAULT_ROLE:-USER}"

if ! command -v jq >/dev/null 2>&1; then
  echo "jq komutu gerekli. Lütfen kurup tekrar deneyin." >&2
  exit 1
fi

usage() {
  cat <<EOF
Kullanıcıyı Keycloak'ta açtıktan sonra user-service veritabanına provision etmek için kullanılır.

Kullanım:
  $(basename "$0") --email user@example.com --name "Ad Soyad" [--role ROLE] [--enabled true|false] [--session-timeout 15]

Gerekli ortam değişkenleri:
  SERVICE_CLIENT_SECRET   Keycloak'tan alınan client_credentials client secret (boşsa hata verir)
Opsiyonel ortam değişkenleri:
  USER_SERVICE_URL        Varsayılan: ${USER_SERVICE_URL}
  SERVICE_TOKEN_URL       Varsayılan: ${SERVICE_TOKEN_URL}
  SERVICE_CLIENT_ID       Varsayılan: ${SERVICE_CLIENT_ID}
  SERVICE_TOKEN_AUDIENCE  Varsayılan: ${SERVICE_TOKEN_AUDIENCE}
  SERVICE_TOKEN_PERMISSIONS  Varsayılan: ${SERVICE_TOKEN_PERMISSIONS}
EOF
  exit 1
}

EMAIL=""
NAME=""
ROLE="$DEFAULT_ROLE"
ENABLED="true"
SESSION_TIMEOUT=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --email)
      EMAIL="$2"
      shift 2
      ;;
    --name)
      NAME="$2"
      shift 2
      ;;
    --role)
      ROLE="$2"
      shift 2
      ;;
    --enabled)
      ENABLED="$2"
      shift 2
      ;;
    --session-timeout)
      SESSION_TIMEOUT="$2"
      shift 2
      ;;
    --help|-h)
      usage
      ;;
    *)
      echo "Bilinmeyen argüman: $1" >&2
      usage
      ;;
  esac
done

if [[ -z "$EMAIL" || -z "$NAME" ]]; then
  usage
fi

if [[ -z "$SERVICE_CLIENT_SECRET" ]]; then
  echo "SERVICE_CLIENT_SECRET tanımlı olmalı." >&2
  exit 1
fi

if [[ "$ENABLED" != "true" && "$ENABLED" != "false" ]]; then
  echo "--enabled yalnızca true/false olabilir" >&2
  exit 1
fi

mint_service_token() {
  local data=(-d grant_type=client_credentials -d "audience=${SERVICE_TOKEN_AUDIENCE}")
  IFS=',' read -ra perms <<< "${SERVICE_TOKEN_PERMISSIONS}"
  for perm in "${perms[@]}"; do
    if [[ -n "$perm" ]]; then
      data+=(-d "permissions=${perm}")
    fi
  done
  local response
  response=$(curl -sS -u "${SERVICE_CLIENT_ID}:${SERVICE_CLIENT_SECRET}" \
    "${data[@]}" \
    "${SERVICE_TOKEN_URL}")
  local token
  token=$(echo "$response" | jq -r '.access_token // empty')
  if [[ -z "$token" ]]; then
    echo "Service token alınamadı: $response" >&2
    exit 1
  fi
  echo "$token"
}

build_payload() {
  local payload
  if [[ -n "$SESSION_TIMEOUT" ]]; then
    payload=$(jq -n \
      --arg email "$EMAIL" \
      --arg name "$NAME" \
      --arg role "$ROLE" \
      --argjson enabled "$ENABLED" \
      --argjson timeout "$SESSION_TIMEOUT" \
      '{email:$email,name:$name,role:$role,enabled:$enabled,sessionTimeoutMinutes:$timeout}')
  else
    payload=$(jq -n \
      --arg email "$EMAIL" \
      --arg name "$NAME" \
      --arg role "$ROLE" \
      --argjson enabled "$ENABLED" \
      '{email:$email,name:$name,role:$role,enabled:$enabled}')
  fi
  echo "$payload"
}

TOKEN="$(mint_service_token)"
PAYLOAD="$(build_payload)"

response=$(curl -sS -X POST "${USER_SERVICE_URL}/api/v1/users/internal/provision" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD")

email=$(echo "$response" | jq -r '.email // empty')
if [[ -z "$email" ]]; then
  echo "Provision başarısız: $response" >&2
  exit 1
fi

echo "Provision tamamlandı: ${email}"
