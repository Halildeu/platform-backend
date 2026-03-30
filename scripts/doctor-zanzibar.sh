#!/usr/bin/env bash
# ============================================================================
# Doctor Zanzibar — OpenFGA Authorization Health Check
# Verifies the entire Zanzibar auth stack is correctly configured.
#
# Usage: ./scripts/doctor-zanzibar.sh [--fix]
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$SCRIPT_DIR/.."
WEB_DIR="$BACKEND_DIR/../web"
FIX_MODE="${1:-}"
ERRORS=0
WARNINGS=0

red() { echo -e "\033[31m$1\033[0m"; }
green() { echo -e "\033[32m$1\033[0m"; }
yellow() { echo -e "\033[33m$1\033[0m"; }

pass() { green "  ✅ $1"; }
fail() { red "  ❌ $1"; ERRORS=$((ERRORS + 1)); }
warn() { yellow "  ⚠️  $1"; WARNINGS=$((WARNINGS + 1)); }

echo "============================================"
echo "  Doctor Zanzibar — Auth Health Check"
echo "============================================"
echo ""

# ── 1. SecurityConfigLocal: JWT doğrulaması olmamalı ──────────────
echo "1. SecurityConfigLocal — JWT validation must be OFF in local profile"
for svc in user-service variant-service core-data-service report-service api-gateway; do
  file=$(find "$BACKEND_DIR/$svc" -name "SecurityConfigLocal.java" -not -path "*/target/*" 2>/dev/null | head -1)
  if [ -n "$file" ]; then
    jwt_refs=$(grep -v '^\s*//' "$file" | grep -v '^\s*\*' | grep -c "oauth2ResourceServer" 2>/dev/null || echo 0)
    if [ "$jwt_refs" -gt 0 ]; then
      fail "$svc/SecurityConfigLocal: oauth2ResourceServer VAR — local'de JWT zorlar"
    else
      pass "$svc/SecurityConfigLocal: JWT yok"
    fi
  else
    if [ "$svc" = "api-gateway" ] || [ "$svc" = "auth-service" ]; then
      warn "$svc: SecurityConfigLocal yok (gateway/auth farklı yönetilir)"
    else
      fail "$svc: SecurityConfigLocal MISSING"
    fi
  fi
done
echo ""

# ── 2. SecurityConfig @Profile: local'de devre dışı olmalı ───────
echo "2. SecurityConfig — must have @Profile(\"!local & !dev\")"
for svc in user-service variant-service core-data-service report-service; do
  file=$(find "$BACKEND_DIR/$svc" -name "SecurityConfig.java" -not -name "*Local*" -not -path "*/target/*" 2>/dev/null | head -1)
  if [ -n "$file" ]; then
    has_profile=$(grep -c '!local' "$file" 2>/dev/null || echo 0)
    if [ "$has_profile" -eq 0 ]; then
      fail "$svc/SecurityConfig: @Profile YOK — local'de JWT zorunlu olur"
    else
      pass "$svc/SecurityConfig: @Profile doğru"
    fi
  fi
done
echo ""

# ── 3. OpenFGA filter order: LOWEST_PRECEDENCE olmalı ────────────
echo "3. OpenFgaAuthzConfig — filter order must be LOWEST_PRECEDENCE"
for svc in user-service variant-service core-data-service; do
  file=$(find "$BACKEND_DIR/$svc" -name "OpenFgaAuthzConfig.java" -not -path "*/target/*" 2>/dev/null | head -1)
  if [ -n "$file" ]; then
    has_lowest=$(grep -c "LOWEST_PRECEDENCE" "$file" 2>/dev/null || echo 0)
    has_highest=$(grep -c "HIGHEST_PRECEDENCE" "$file" 2>/dev/null || echo 0)
    if [ "$has_highest" -gt 0 ]; then
      fail "$svc: filter order HIGHEST_PRECEDENCE — userId null olur, LOWEST_PRECEDENCE olmalı"
    elif [ "$has_lowest" -gt 0 ]; then
      pass "$svc: filter order LOWEST_PRECEDENCE"
    else
      warn "$svc: filter order belirsiz"
    fi
  else
    warn "$svc: OpenFgaAuthzConfig yok"
  fi
done
echo ""

# ── 4. Vite proxy: 8090 (permission-service) kullanılmamalı ──────
echo "4. Vite proxy — port 8090 (permission-service) must NOT be used"
VITE_CONFIG="$WEB_DIR/apps/mfe-shell/vite.config.ts"
if [ -f "$VITE_CONFIG" ]; then
  authz_target=$(grep "api/v1/authz" "$VITE_CONFIG" | grep -o "localhost:[0-9]*" | head -1)
  if [ "$authz_target" = "localhost:8090" ]; then
    fail "vite /api/v1/authz → 8090 (permission-service REMOVED) — 8089 olmalı"
  elif [ "$authz_target" = "localhost:8089" ]; then
    pass "vite /api/v1/authz → 8089 (user-service)"
  else
    warn "vite /api/v1/authz → ${authz_target:-BULUNAMADI}"
  fi

  has_8090=$(grep -c "localhost:8090" "$VITE_CONFIG" 2>/dev/null || echo 0)
  if [ "$has_8090" -gt 0 ]; then
    fail "vite.config.ts hala 8090 portu referans ediyor ($has_8090 yerde)"
  else
    pass "vite.config.ts: 8090 referansı yok"
  fi
else
  warn "vite.config.ts bulunamadı"
fi
echo ""

# ── 5. use-authorization: OpenFGA module mapping olmalı ──────────
echo "5. useAuthorization — OpenFGA module→legacy mapping must exist"
AUTH_HOOK="$WEB_DIR/apps/mfe-shell/src/features/auth/model/use-authorization.model.ts"
if [ -f "$AUTH_HOOK" ]; then
  has_mapping=$(grep -c "USER_MANAGEMENT" "$AUTH_HOOK" 2>/dev/null || echo 0)
  if [ "$has_mapping" -gt 0 ]; then
    pass "useAuthorization: OpenFGA module mapping var"
  else
    fail "useAuthorization: OpenFGA module mapping YOK — menüler gizlenir"
  fi
else
  fail "use-authorization.model.ts bulunamadı"
fi
echo ""

# ── 6. Report-service: MockPermission local/dev profile ──────────
echo "6. Report-service — MockPermissionServiceClient must cover local/dev"
MOCK_FILE=$(find "$BACKEND_DIR/report-service" -name "MockPermissionServiceClient.java" -not -path "*/target/*" 2>/dev/null | head -1)
if [ -n "$MOCK_FILE" ]; then
  has_local=$(grep -c '"local"' "$MOCK_FILE" 2>/dev/null || echo 0)
  if [ "$has_local" -gt 0 ]; then
    pass "MockPermissionServiceClient: local profile dahil"
  else
    fail "MockPermissionServiceClient: sadece conntest — local'de JWT gerekir"
  fi
else
  fail "MockPermissionServiceClient bulunamadı"
fi
echo ""

# ── 7. Permission-service durumu ─────────────────────────────────
echo "7. Permission-service — must be REMOVED or legacy-only"
PERM_COMPOSE=$(grep -c 'profiles.*legacy' "$BACKEND_DIR/docker-compose.yml" 2>/dev/null || echo 0)
if [ "$PERM_COMPOSE" -gt 0 ]; then
  pass "docker-compose: permission-service profiles=[legacy]"
else
  PERM_EXISTS=$(grep -c 'permission-service:' "$BACKEND_DIR/docker-compose.yml" 2>/dev/null || echo 0)
  if [ "$PERM_EXISTS" -gt 0 ]; then
    fail "docker-compose: permission-service aktif (legacy profili yok)"
  else
    pass "docker-compose: permission-service tamamen kaldırılmış"
  fi
fi
echo ""

# ── 8. OpenFGA container çalışıyor mu ────────────────────────────
echo "8. OpenFGA container — must be running"
OPENFGA_HEALTH=$(curl -sf "http://localhost:4000/healthz" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null || echo "UNREACHABLE")
if [ "$OPENFGA_HEALTH" = "SERVING" ]; then
  pass "OpenFGA: SERVING"
else
  warn "OpenFGA: $OPENFGA_HEALTH (container kapalı olabilir)"
fi
echo ""

# ── 9. .claude/rules auth kuralları ──────────────────────────────
echo "9. Claude rules — auth protection rules must exist"
WEB_RULES="$BACKEND_DIR/../.claude/rules/web-apps.md"
BACKEND_RULES="$BACKEND_DIR/../.claude/rules/backend-services.md"
if [ -f "$WEB_RULES" ]; then
  has_auth=$(grep -c "NEVER.*8090\|NEVER.*AUTH_MODE\|NEVER.*proxy" "$WEB_RULES" 2>/dev/null || echo 0)
  if [ "$has_auth" -gt 0 ]; then
    pass "web-apps.md: auth koruma kuralları var"
  else
    warn "web-apps.md: auth koruma kuralları eksik"
  fi
fi
if [ -f "$BACKEND_RULES" ]; then
  has_auth=$(grep -c "LOWEST_PRECEDENCE\|permission-service.*REMOVED" "$BACKEND_RULES" 2>/dev/null || echo 0)
  if [ "$has_auth" -gt 0 ]; then
    pass "backend-services.md: auth koruma kuralları var"
  else
    warn "backend-services.md: auth koruma kuralları eksik"
  fi
fi
echo ""

# ── Sonuç ────────────────────────────────────────────────────────
echo "============================================"
if [ "$ERRORS" -eq 0 ]; then
  green "  PASS: $ERRORS hata, $WARNINGS uyarı"
else
  red "  FAIL: $ERRORS hata, $WARNINGS uyarı"
fi
echo "============================================"
exit "$ERRORS"
