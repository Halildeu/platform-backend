#!/usr/bin/env bash
set -euo pipefail

# Basit entegrasyon testi: login → users/variants endpointlerini
# Authorization olmadan ve token ile çağırıp HTTP kodlarını raporlar.

ADMIN_EMAIL="${ADMIN_EMAIL:-admin@example.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin1234}"
COMPANY_ID="${COMPANY_ID:-1}"
BASE="${BASE_URL:-http://localhost:8080}"

echo "[login] ${ADMIN_EMAIL} @ companyId=${COMPANY_ID}"
LOGIN_JSON=$(curl -s -X POST "${BASE}/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASSWORD}\",\"companyId\":${COMPANY_ID}}" ) || true

TOKEN=$(printf '%s' "$LOGIN_JSON" | jq -r '.token // empty')
if [[ -z "$TOKEN" ]]; then
  echo "[login] Başarısız veya token boş. Yanıt:" >&2
  echo "$LOGIN_JSON" | sed -n '1,80p'
  exit 1
fi
echo "[login] OK (token alındı)"

users_no_auth=$(curl -s -o /dev/null -w "%{http_code}" "${BASE}/api/users/all?page=1&pageSize=50")
users_with_auth=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer ${TOKEN}" "${BASE}/api/users/all?page=1&pageSize=50")

variants_no_auth=$(curl -s -o /dev/null -w "%{http_code}" "${BASE}/api/variants?gridId=mfe-users%2Fusers-grid")
variants_with_auth=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer ${TOKEN}" "${BASE}/api/variants?gridId=mfe-users%2Fusers-grid")

echo "[users/all]   no-auth=${users_no_auth}  with-auth=${users_with_auth}"
echo "[variants]    no-auth=${variants_no_auth}  with-auth=${variants_with_auth}"

# Basit değerlendirme: with-auth çağrıları 200 değilse hata say.
err=0
[[ "$users_with_auth" == "200" ]] || { echo "[fail] users/all with-auth HTTP ${users_with_auth}"; err=1; }
[[ "$variants_with_auth" == "200" ]] || { echo "[fail] variants with-auth HTTP ${variants_with_auth}"; err=1; }

if [[ $err -eq 0 ]]; then
  echo "[ok] Tüm with-auth çağrıları 200 döndü."
else
  echo "[hint] Hata için servis loglarını inceleyin:"
  echo "  docker compose logs --tail=200 api-gateway user-service variant-service"
  exit 2
fi

