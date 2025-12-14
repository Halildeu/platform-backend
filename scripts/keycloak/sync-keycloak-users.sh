#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

KEYCLOAK_BASE_URL="${KEYCLOAK_BASE_URL:-http://localhost:8081}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-serban}"
KEYCLOAK_ADMIN_USERNAME="${KEYCLOAK_ADMIN_USERNAME:-admin}"
KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
KEYCLOAK_ADMIN_CLIENT_ID="${KEYCLOAK_ADMIN_CLIENT_ID:-admin-cli}"
KEYCLOAK_PAGE_SIZE="${KEYCLOAK_PAGE_SIZE:-50}"
SYNC_FALLBACK_ROLE="${SYNC_FALLBACK_ROLE:-USER}"

if ! command -v jq >/dev/null 2>&1; then
  echo "jq komutu gerekli. Lütfen kurup tekrar deneyin." >&2
  exit 1
fi

token_endpoint="${KEYCLOAK_BASE_URL}/realms/master/protocol/openid-connect/token"
admin_token=$(curl -sS -X POST "${token_endpoint}" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" \
  -d "client_id=${KEYCLOAK_ADMIN_CLIENT_ID}" \
  -d "username=${KEYCLOAK_ADMIN_USERNAME}" \
  -d "password=${KEYCLOAK_ADMIN_PASSWORD}" | jq -r '.access_token // empty')

if [[ -z "$admin_token" ]]; then
  echo "Keycloak admin token alınamadı. Kullanıcı adı/şifre doğru mu?" >&2
  exit 1
fi

first=0
total_synced=0

fetch_users() {
  local offset=$1
  curl -sS -H "Authorization: Bearer ${admin_token}" \
    "${KEYCLOAK_BASE_URL}/admin/realms/${KEYCLOAK_REALM}/users?first=${offset}&max=${KEYCLOAK_PAGE_SIZE}&briefRepresentation=false"
}

while true; do
  payload=$(fetch_users "$first")
  batch_count=$(echo "$payload" | jq 'length')
  if [[ "$batch_count" -eq 0 ]]; then
    break
  fi

  echo ">> Keycloak kullanıcıları senkronize ediliyor (first=${first}, batch=${batch_count})"
  while IFS= read -r row; do
    email=$(echo "$row" | jq -r '.email // .username // empty')
    if [[ -z "$email" ]]; then
      echo "   - e-posta bulunamadı, kullanıcı atlandı." >&2
      continue
    fi
    if [[ "$email" != *@* ]]; then
      echo "   - e-posta biçimi geçersiz (${email}), kullanıcı atlandı." >&2
      continue
    fi
    first_name=$(echo "$row" | jq -r '.firstName // empty')
    last_name=$(echo "$row" | jq -r '.lastName // empty')
    username=$(echo "$row" | jq -r '.username // empty')
    if [[ -n "$first_name" || -n "$last_name" ]]; then
      name="$(echo "${first_name} ${last_name}" | xargs)"
    else
      name="${username}"
    fi
    enabled=$(echo "$row" | jq -r '.enabled // true')
    role=$(echo "$row" | jq -r '
      if (.attributes.provisionRole[0]? // "") != "" then .attributes.provisionRole[0]
      elif (.realmRoles? // [] | map(ascii_upcase) | index("ADMIN")) then "ADMIN"
      else "USER"
      end')

    if [[ -z "$role" || "$role" == "null" ]]; then
      role="${SYNC_FALLBACK_ROLE}"
    fi

    "${SCRIPT_DIR}/provision-user.sh" \
      --email "${email}" \
      --name "${name}" \
      --role "${role}" \
      --enabled "$(echo "$enabled" | tr '[:upper:]' '[:lower:]')"
    total_synced=$((total_synced + 1))
  done < <(echo "$payload" | jq -c '.[]')

  first=$((first + KEYCLOAK_PAGE_SIZE))
done

echo "Toplam ${total_synced} kullanıcı senkronize edildi."
