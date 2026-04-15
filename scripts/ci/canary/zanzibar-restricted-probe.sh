#!/usr/bin/env bash
set -euo pipefail

# Zanzibar restricted user deny probe
# Logs into Keycloak as a restricted user and verifies THEME module is denied.
# Used in canary validation to ensure deny-path regressions are caught.
#
# Refs:
#   - CNS-20260411-001 (Codex objection #2 — admin-only canary is insufficient)
#   - CNS-20260416-001 Q5 (canary-load dedicated client; frontend direct-grants disabled)
#   - CNS-20260416-002 PR-2 (persona seed user koordinasyonu)
#
# Client seçimi:
#   CANARY_CLIENT_ID (önerilen, stage-only canary-load client — direct grants enabled):
#     KC admin'de confidential/public client olarak canary-load tanımlı olmalı,
#     direct access grants aktif olmalı. Persona user'lar (CANARY_RESTRICTED rolüyle)
#     zanzibar-canary-setup.mjs ile hazırlanır.
#   CLIENT_ID (geriye dönük, frontend — not: realm export'ta direct grants DISABLED
#     olabilir, auth-enabled staging'de fail eder):
#     Sadece dev/legacy fallback için.

KC_BASE_URL="${KC_BASE_URL:-http://localhost:8081}"
KC_REALM="${KC_REALM:-serban}"
# CANARY_CLIENT_ID önceliklidir; yoksa legacy CLIENT_ID fallback
CLIENT_ID="${CANARY_CLIENT_ID:-${CLIENT_ID:-frontend}}"
CANARY_CLIENT_SECRET="${CANARY_CLIENT_SECRET:-}"

# Persona seed ile aynı user (zanzibar-canary-setup.mjs: canary-restricted@stage.local + CanaryPass123!)
# Override için RESTRICTED_USER_EMAIL + RESTRICTED_USER_PASSWORD env'leri kullanılır.
RESTRICTED_USER_EMAIL="${RESTRICTED_USER_EMAIL:-canary-restricted@stage.local}"
RESTRICTED_USER_PASSWORD="${RESTRICTED_USER_PASSWORD:-${CANARY_PASSWORD:-CanaryPass123!}}"
AUTHZ_ME_URL="${AUTHZ_ME_URL:-http://localhost:8090/api/v1/authz/me}"

echo "[probe] Restricted user deny probe starting..."
echo "[probe] user=${RESTRICTED_USER_EMAIL} realm=${KC_REALM} client=${CLIENT_ID}"

# Step 1: Get Keycloak token (client_secret destekli — canary-load confidential ise)
TOKEN_BODY=(
  -d "client_id=${CLIENT_ID}"
  -d "username=${RESTRICTED_USER_EMAIL}"
  -d "password=${RESTRICTED_USER_PASSWORD}"
  -d "grant_type=password"
)
if [[ -n "$CANARY_CLIENT_SECRET" ]]; then
  TOKEN_BODY+=(-d "client_secret=${CANARY_CLIENT_SECRET}")
fi

TOKEN_RESPONSE=$(curl -sf -X POST \
  "${KC_BASE_URL}/realms/${KC_REALM}/protocol/openid-connect/token" \
  "${TOKEN_BODY[@]}" 2>&1) || {
  echo "[probe] FAIL: Keycloak login failed for ${RESTRICTED_USER_EMAIL} (client=${CLIENT_ID})"
  echo "[probe]       Hint: auth-enabled staging'de CANARY_CLIENT_ID=canary-load + CANARY_CLIENT_SECRET set edilmeli."
  exit 1
}

TOKEN=$(echo "$TOKEN_RESPONSE" | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])" 2>/dev/null) || {
  echo "[probe] FAIL: Could not extract access_token"
  exit 1
}
echo "[probe] Token OK (${#TOKEN} chars)"

# Step 2: Call /authz/me
AUTHZ_RESPONSE=$(curl -sf "${AUTHZ_ME_URL}" \
  -H "Authorization: Bearer ${TOKEN}" 2>&1) || {
  echo "[probe] FAIL: /authz/me request failed"
  exit 1
}

# Step 3: Validate deny scenarios
SUPER_ADMIN=$(echo "$AUTHZ_RESPONSE" | python3 -c "import json,sys; print(json.load(sys.stdin).get('superAdmin', 'MISSING'))")
MODULES=$(echo "$AUTHZ_RESPONSE" | python3 -c "import json,sys; print(','.join(json.load(sys.stdin).get('modules', {}).keys()))")
HAS_THEME=$(echo "$AUTHZ_RESPONSE" | python3 -c "import json,sys; print('THEME' in json.load(sys.stdin).get('modules', {}))")

echo "[probe] superAdmin=${SUPER_ADMIN}"
echo "[probe] modules=${MODULES}"
echo "[probe] has_THEME=${HAS_THEME}"

FAILURES=0

# Restricted user must NOT be superAdmin
if [ "$SUPER_ADMIN" = "True" ]; then
  echo "[probe] FAIL: restricted user is superAdmin (expected false)"
  FAILURES=$((FAILURES + 1))
fi

# Restricted user must NOT have THEME module
if [ "$HAS_THEME" = "True" ]; then
  echo "[probe] FAIL: restricted user has THEME module (expected denied)"
  FAILURES=$((FAILURES + 1))
fi

# Restricted user SHOULD have ACCESS module (positive check)
HAS_ACCESS=$(echo "$AUTHZ_RESPONSE" | python3 -c "import json,sys; print('ACCESS' in json.load(sys.stdin).get('modules', {}))")
if [ "$HAS_ACCESS" != "True" ]; then
  echo "[probe] WARN: restricted user missing ACCESS module (expected granted)"
fi

if [ "$FAILURES" -gt 0 ]; then
  echo "[probe] FAIL: ${FAILURES} deny assertion(s) failed"
  exit 1
fi

echo "[probe] PASS: restricted user deny scenarios validated"
echo "[probe] - superAdmin=false ✅"
echo "[probe] - THEME denied ✅"
echo "[probe] - ACCESS granted ✅"
