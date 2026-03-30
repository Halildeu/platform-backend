#!/usr/bin/env bash
# ============================================================================
# Doctor Zanzibar — Full OpenFGA Authorization Stack Health Check
#
# Verifies the entire Zanzibar auth stack: Keycloak (authn), OpenFGA (authz),
# data enforcement (Hibernate @Filter, RLS), frontend integration, and
# runtime service health.
#
# Usage:
#   ./scripts/doctor-zanzibar.sh          # Full check
#   ./scripts/doctor-zanzibar.sh --quick  # Skip runtime checks (no Docker needed)
#   ./scripts/doctor-zanzibar.sh --json   # JSON output
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$SCRIPT_DIR/.."
WEB_DIR="$BACKEND_DIR/../web"
QUICK_MODE=false
JSON_MODE=false
for arg in "$@"; do
  [ "$arg" = "--quick" ] && QUICK_MODE=true
  [ "$arg" = "--json" ] && JSON_MODE=true
done

ERRORS=0
WARNINGS=0
CHECKS=0
RESULTS=()

red() { $JSON_MODE || echo -e "\033[31m$1\033[0m"; }
green() { $JSON_MODE || echo -e "\033[32m$1\033[0m"; }
yellow() { $JSON_MODE || echo -e "\033[33m$1\033[0m"; }
header() { $JSON_MODE || echo -e "\n\033[1;36m$1\033[0m"; }

pass() { green "  ✅ $1"; CHECKS=$((CHECKS + 1)); RESULTS+=("{\"check\":\"$1\",\"status\":\"pass\"}"); }
fail() { red "  ❌ $1"; ERRORS=$((ERRORS + 1)); CHECKS=$((CHECKS + 1)); RESULTS+=("{\"check\":\"$1\",\"status\":\"fail\"}"); }
warn() { yellow "  ⚠️  $1"; WARNINGS=$((WARNINGS + 1)); CHECKS=$((CHECKS + 1)); RESULTS+=("{\"check\":\"$1\",\"status\":\"warn\"}"); }
skip() { $JSON_MODE || echo "  ⏭️  $1 (skipped)"; }

$JSON_MODE || echo "============================================"
$JSON_MODE || echo "  Doctor Zanzibar — Auth Health Check"
$JSON_MODE || echo "============================================"

# ═══════════════════════════════════════════════════════════════════
# SECTION A: CODE-LEVEL CHECKS (no Docker needed)
# ═══════════════════════════════════════════════════════════════════

# ── A1. SecurityConfigLocal: JWT doğrulaması olmamalı ─────────────
header "A1. SecurityConfigLocal — JWT validation must be OFF in local profile"
for svc in user-service variant-service core-data-service report-service api-gateway; do
  file=$(find "$BACKEND_DIR/$svc" -name "SecurityConfigLocal.java" -not -path "*/target/*" 2>/dev/null | head -1)
  if [ -n "$file" ]; then
    jwt_refs=$(grep -v '^\s*//' "$file" | grep -v '^\s*\*' | grep -c "oauth2ResourceServer" 2>/dev/null || true)
    jwt_refs=${jwt_refs:-0}
    if [ "$jwt_refs" -gt 0 ]; then
      fail "$svc: oauth2ResourceServer in SecurityConfigLocal — JWT zorlar"
    else
      pass "$svc: SecurityConfigLocal JWT-free"
    fi
  else
    case "$svc" in
      api-gateway|auth-service) warn "$svc: SecurityConfigLocal yok (farklı yönetilir)" ;;
      *) fail "$svc: SecurityConfigLocal MISSING" ;;
    esac
  fi
done

# ── A2. SecurityConfig @Profile: local'de devre dışı olmalı ──────
header "A2. SecurityConfig — must have @Profile(\"!local & !dev\")"
for svc in user-service variant-service core-data-service report-service; do
  file=$(find "$BACKEND_DIR/$svc" -name "SecurityConfig.java" -not -name "*Local*" -not -path "*/target/*" 2>/dev/null | head -1)
  if [ -n "$file" ]; then
    has_profile=$(grep -c '!local' "$file" 2>/dev/null || true)
    has_profile=${has_profile:-0}
    [ "$has_profile" -gt 0 ] && pass "$svc: SecurityConfig @Profile doğru" || fail "$svc: SecurityConfig @Profile YOK"
  fi
done

# ── A3. OpenFGA filter order ─────────────────────────────────────
header "A3. ScopeContextFilter order — must be LOWEST_PRECEDENCE (after Security)"
for svc in user-service variant-service core-data-service; do
  file=$(find "$BACKEND_DIR/$svc" -name "OpenFgaAuthzConfig.java" -not -path "*/target/*" 2>/dev/null | head -1)
  if [ -n "$file" ]; then
    has_highest=$(grep -v '^\s*//' "$file" | grep -c "HIGHEST_PRECEDENCE" 2>/dev/null || true)
    has_highest=${has_highest:-0}
    has_lowest=$(grep -v '^\s*//' "$file" | grep -c "LOWEST_PRECEDENCE" 2>/dev/null || true)
    has_lowest=${has_lowest:-0}
    if [ "$has_highest" -gt 0 ]; then
      fail "$svc: filter order HIGHEST_PRECEDENCE — userId null olur"
    elif [ "$has_lowest" -gt 0 ]; then
      pass "$svc: filter order LOWEST_PRECEDENCE"
    else
      warn "$svc: filter order belirsiz"
    fi
  else
    warn "$svc: OpenFgaAuthzConfig yok"
  fi
done

# ── A4. Hibernate @Filter: companyId entity'lerde olmalı ─────────
header "A4. Hibernate @Filter — entities with companyId must have @Filter"
for svc in user-service permission-service variant-service; do
  entities=$(grep -rl "company_id\|companyId" "$BACKEND_DIR/$svc/src/main/java" --include="*.java" 2>/dev/null | grep -i "model\|entity" | grep -v target || true)
  for entity in $entities; do
    name=$(basename "$entity" .java)
    has_filter=$(grep -c "@Filter" "$entity" 2>/dev/null || true)
    has_filter=${has_filter:-0}
    [ "$has_filter" -gt 0 ] && pass "$svc/$name: @Filter var" || warn "$svc/$name: @Filter YOK (companyId var ama filtre yok)"
  done
done

# ── A5. PostgreSQL RLS policy dosyası ────────────────────────────
header "A5. PostgreSQL RLS — policy file must exist"
RLS_FILE="$BACKEND_DIR/devops/postgres/02-rls-policies.sql"
if [ -f "$RLS_FILE" ]; then
  rls_count=$(grep -c "ENABLE ROW LEVEL SECURITY" "$RLS_FILE" 2>/dev/null || true)
  rls_count=${rls_count:-0}
  pass "02-rls-policies.sql: $rls_count tablo RLS aktif"
else
  warn "02-rls-policies.sql bulunamadı"
fi

# ── A6. @BypassScopeFilter kullanımı ─────────────────────────────
header "A6. @BypassScopeFilter — usage audit"
bypass_count=$(find "$BACKEND_DIR" -name "*.java" -not -path "*/target/*" -exec grep -l "@BypassScopeFilter" {} \; 2>/dev/null | wc -l | tr -d ' ')
bypass_annotation=$(find "$BACKEND_DIR" -name "BypassScopeFilter.java" -not -path "*/target/*" 2>/dev/null | head -1)
if [ -n "$bypass_annotation" ]; then
  pass "BypassScopeFilter annotation tanımlı"
  [ "$bypass_count" -gt 1 ] && warn "BypassScopeFilter $bypass_count yerde kullanılıyor (audit gerekli)" || pass "BypassScopeFilter kullanımı minimal ($bypass_count)"
else
  warn "BypassScopeFilter annotation bulunamadı"
fi

# ── A7. Vite proxy yapılandırması ────────────────────────────────
header "A7. Vite proxy — routing correctness"
VITE_CONFIG="$WEB_DIR/apps/mfe-shell/vite.config.ts"
if [ -f "$VITE_CONFIG" ]; then
  # 8090 (permission-service) referansı olmamalı
  has_8090=$(grep -v '^\s*//' "$VITE_CONFIG" | grep -c "localhost:8090" 2>/dev/null || true)
  has_8090=${has_8090:-0}
  [ "$has_8090" -gt 0 ] && fail "vite: 8090 (permission-service) referansı var" || pass "vite: 8090 referansı yok"

  # authz → 8089 olmalı
  authz_target=$(grep "api/v1/authz" "$VITE_CONFIG" | grep -o "localhost:[0-9]*" | head -1)
  [ "$authz_target" = "localhost:8089" ] && pass "vite: /api/v1/authz → 8089" || fail "vite: /api/v1/authz → ${authz_target:-TANIMSIZ} (8089 olmalı)"

  # companies direct route olmalı
  has_companies=$(grep -c "api/v1/companies" "$VITE_CONFIG" 2>/dev/null || true)
  has_companies=${has_companies:-0}
  [ "$has_companies" -gt 0 ] && pass "vite: /api/v1/companies direct route var" || warn "vite: /api/v1/companies direct route yok (gateway üzerinden gider)"

  # reports direct route olmalı
  has_reports=$(grep -c "api/v1/reports" "$VITE_CONFIG" 2>/dev/null || true)
  has_reports=${has_reports:-0}
  [ "$has_reports" -gt 0 ] && pass "vite: /api/v1/reports direct route var" || warn "vite: /api/v1/reports direct route yok"
else
  fail "vite.config.ts bulunamadı"
fi

# ── A8. Frontend useAuthorization mapping ────────────────────────
header "A8. useAuthorization — OpenFGA module→legacy permission mapping"
AUTH_HOOK="$WEB_DIR/apps/mfe-shell/src/features/auth/model/use-authorization.model.ts"
if [ -f "$AUTH_HOOK" ]; then
  for module in USER_MANAGEMENT ACCESS AUDIT REPORT THEME WAREHOUSE; do
    has=$(grep -c "'$module'" "$AUTH_HOOK" 2>/dev/null || true)
    has=${has:-0}
    [ "$has" -gt 0 ] && pass "mapping: $module → legacy" || fail "mapping: $module eksik — menü gizlenir"
  done
else
  fail "use-authorization.model.ts bulunamadı"
fi

# ── A9. @mfe/auth package ────────────────────────────────────────
header "A9. @mfe/auth package — must exist and be integrated"
AUTH_PKG="$WEB_DIR/packages/auth/src/index.ts"
if [ -f "$AUTH_PKG" ]; then
  has_provider=$(grep -c "PermissionProvider" "$AUTH_PKG" 2>/dev/null || true)
  has_provider=${has_provider:-0}
  [ "$has_provider" -gt 0 ] && pass "@mfe/auth: PermissionProvider export var" || fail "@mfe/auth: PermissionProvider export yok"

  # AppProviders'da entegre mi?
  APP_PROVIDERS="$WEB_DIR/apps/mfe-shell/src/app/providers/AppProviders.tsx"
  if [ -f "$APP_PROVIDERS" ]; then
    has_import=$(grep -c "PermissionProvider" "$APP_PROVIDERS" 2>/dev/null || true)
    has_import=${has_import:-0}
    [ "$has_import" -gt 0 ] && pass "AppProviders: PermissionProvider entegre" || warn "AppProviders: PermissionProvider entegre değil"
  fi
else
  warn "@mfe/auth package bulunamadı"
fi

# ── A10. Report-service mock/real profile ayrımı ─────────────────
header "A10. Report-service — MockPermission for local, real for prod"
MOCK_FILE=$(find "$BACKEND_DIR/report-service" -name "MockPermissionServiceClient.java" -not -path "*/target/*" 2>/dev/null | head -1)
REAL_FILE=$(find "$BACKEND_DIR/report-service" -name "PermissionServiceClient.java" -not -path "*/target/*" -not -name "Mock*" 2>/dev/null | head -1)
if [ -n "$MOCK_FILE" ]; then
  has_local=$(grep -c '"local"' "$MOCK_FILE" 2>/dev/null || true)
  has_local=${has_local:-0}
  [ "$has_local" -gt 0 ] && pass "Mock: local profile dahil" || fail "Mock: sadece conntest — local'de JWT gerekir"
fi
if [ -n "$REAL_FILE" ]; then
  excludes_local=$(grep -c '!local' "$REAL_FILE" 2>/dev/null || true)
  excludes_local=${excludes_local:-0}
  [ "$excludes_local" -gt 0 ] && pass "Real: local profile hariç" || fail "Real: local'de aktif — Mock ile çakışır"
fi

# ── A11. OpenFGA model dosyası ───────────────────────────────────
header "A11. OpenFGA model — must exist with correct hierarchy"
MODEL_FILE="$BACKEND_DIR/openfga/model.fga"
if [ -f "$MODEL_FILE" ]; then
  for type in user company project warehouse module; do
    has=$(grep -c "type $type" "$MODEL_FILE" 2>/dev/null || true)
    has=${has:-0}
    [ "$has" -gt 0 ] && pass "model: type $type tanımlı" || fail "model: type $type eksik"
  done
else
  fail "openfga/model.fga bulunamadı"
fi

# ── A12. Permission-service durumu ───────────────────────────────
header "A12. Permission-service — must be REMOVED or legacy-only"
COMPOSE="$BACKEND_DIR/docker-compose.yml"
perm_legacy=$(grep -c 'profiles.*legacy' "$COMPOSE" 2>/dev/null || true)
perm_legacy=${perm_legacy:-0}
if [ "$perm_legacy" -gt 0 ]; then
  pass "docker-compose: permission-service profiles=[legacy]"
else
  perm_exists=$(grep -c 'permission-service:' "$COMPOSE" 2>/dev/null || true)
  perm_exists=${perm_exists:-0}
  [ "$perm_exists" -gt 0 ] && fail "docker-compose: permission-service aktif" || pass "docker-compose: permission-service kaldırılmış"
fi

# ── A12b. Gateway routes — no PERMISSION-SERVICE references ──────
header "A12b. Gateway routes — PERMISSION-SERVICE must not be referenced"
GW_PROPS="$BACKEND_DIR/api-gateway/src/main/resources/application.properties"
if [ -f "$GW_PROPS" ]; then
  perm_refs=$(grep -c "PERMISSION-SERVICE" "$GW_PROPS" 2>/dev/null || true)
  perm_refs=${perm_refs:-0}
  [ "$perm_refs" -eq 0 ] && pass "gateway: PERMISSION-SERVICE referansı yok" || fail "gateway: $perm_refs PERMISSION-SERVICE referansı var — USER-SERVICE olmalı"
fi

# ── A12c. Schema-service AUTH_MODE ────────────────────────────────
header "A12c. Schema-service — AUTH_MODE must be permitAll in docker"
COMPOSE="$BACKEND_DIR/docker-compose.yml"
schema_auth=$(grep -A20 "schema-service:" "$COMPOSE" | grep "AUTH_MODE" | head -1)
if echo "$schema_auth" | grep -qi "permitAll" 2>/dev/null; then
  pass "schema-service: AUTH_MODE=permitAll"
else
  fail "schema-service: AUTH_MODE permitAll değil — JWT 401 verir"
fi

# ── A13. .env.local koruması ─────────────────────────────────────
header "A13. .env.local — AUTH_MODE must be keycloak"
ENV_LOCAL="$WEB_DIR/apps/mfe-shell/.env.local"
if [ -f "$ENV_LOCAL" ]; then
  auth_mode=$(grep "^AUTH_MODE=" "$ENV_LOCAL" | cut -d= -f2 | tr -d ' ')
  case "$auth_mode" in
    keycloak) pass ".env.local: AUTH_MODE=keycloak" ;;
    permitall|permitAll) warn ".env.local: AUTH_MODE=permitall (dev-only, prod'da keycloak olmalı)" ;;
    *) warn ".env.local: AUTH_MODE=${auth_mode:-TANIMSIZ}" ;;
  esac
else
  warn ".env.local bulunamadı (gitignore'da — normal)"
fi

# ── A14. Claude rules koruma ─────────────────────────────────────
header "A14. Claude rules — auth protection rules"
RULES_DIR="$BACKEND_DIR/../.claude/rules"
for rule_file in web-apps.md backend-services.md; do
  if [ -f "$RULES_DIR/$rule_file" ]; then
    has_auth=$(grep -c "NEVER\|DO NOT\|CRITICAL" "$RULES_DIR/$rule_file" 2>/dev/null || true)
    has_auth=${has_auth:-0}
    [ "$has_auth" -gt 0 ] && pass "$rule_file: koruma kuralları var ($has_auth kural)" || warn "$rule_file: koruma kuralları eksik"
  else
    warn "$rule_file bulunamadı"
  fi
done

# ── A15. ADR dokümantasyonu ──────────────────────────────────────
header "A15. ADR — architecture decision records"
ADR_DIR="$BACKEND_DIR/../docs/02-architecture/services/auth-service"
if [ -d "$ADR_DIR" ]; then
  adr_count=$(ls "$ADR_DIR"/ADR-*.md 2>/dev/null | wc -l | tr -d ' ')
  [ "$adr_count" -gt 0 ] && pass "ADR: $adr_count karar kaydı var" || warn "ADR dizini var ama boş"
else
  warn "ADR dizini bulunamadı"
fi

# ── A16. common-auth OpenFGA SDK ─────────────────────────────────
header "A16. common-auth — OpenFGA SDK integration"
OPENFGA_SVC=$(find "$BACKEND_DIR/common-auth" -name "OpenFgaAuthzService.java" -not -path "*/target/*" 2>/dev/null | head -1)
SCOPE_CTX=$(find "$BACKEND_DIR/common-auth" -name "ScopeContext.java" -not -path "*/target/*" 2>/dev/null | head -1)
SCOPE_FILTER=$(find "$BACKEND_DIR/common-auth" -name "ScopeContextFilter.java" -not -path "*/target/*" 2>/dev/null | head -1)
RLS_HELPER=$(find "$BACKEND_DIR/common-auth" -name "RlsScopeHelper.java" -not -path "*/target/*" 2>/dev/null | head -1)

[ -n "$OPENFGA_SVC" ] && pass "OpenFgaAuthzService mevcut" || fail "OpenFgaAuthzService eksik"
[ -n "$SCOPE_CTX" ] && pass "ScopeContext mevcut" || fail "ScopeContext eksik"
[ -n "$SCOPE_FILTER" ] && pass "ScopeContextFilter mevcut" || fail "ScopeContextFilter eksik"
[ -n "$RLS_HELPER" ] && pass "RlsScopeHelper mevcut" || fail "RlsScopeHelper eksik"

# ── A17. AuthorizationContextBuilder JWT-free ────────────────────
header "A17. AuthorizationContextBuilder — must NOT extract permissions from JWT"
CTX_BUILDER=$(find "$BACKEND_DIR/common-auth" -name "AuthorizationContextBuilder.java" -not -path "*/target/*" 2>/dev/null | head -1)
if [ -n "$CTX_BUILDER" ]; then
  perm_extract=$(grep -v '^\s*//' "$CTX_BUILDER" | grep -c 'getClaim.*permissions' 2>/dev/null || true)
  perm_extract=${perm_extract:-0}
  [ "$perm_extract" -eq 0 ] && pass "JWT'den permission extraction yok" || fail "JWT'den permission extraction var ($perm_extract ref)"

  empty_perms=$(grep -c 'Set\.of()' "$CTX_BUILDER" 2>/dev/null || true)
  empty_perms=${empty_perms:-0}
  [ "$empty_perms" -gt 0 ] && pass "Permissions = Set.of() (identity-only JWT)" || warn "Permissions Set.of() bulunamadı"
fi

# ═══════════════════════════════════════════════════════════════════
# SECTION B: RUNTIME CHECKS (Docker required)
# ═══════════════════════════════════════════════════════════════════

if $QUICK_MODE; then
  $JSON_MODE || echo ""
  $JSON_MODE || echo "⏭️  Runtime checks skipped (--quick mode)"
else

  header "B1. OpenFGA container health"
  OPENFGA_HEALTH=$(curl -sf "http://localhost:4000/healthz" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null || echo "UNREACHABLE")
  [ "$OPENFGA_HEALTH" = "SERVING" ] && pass "OpenFGA: SERVING" || warn "OpenFGA: $OPENFGA_HEALTH"

  header "B2. OpenFGA store & model"
  STORE_COUNT=$(curl -sf "http://localhost:4000/stores" 2>/dev/null | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('stores',[])))" 2>/dev/null || echo "0")
  [ "$STORE_COUNT" -gt 0 ] && pass "OpenFGA: $STORE_COUNT store mevcut" || warn "OpenFGA: store yok (init.sh çalıştırılmalı)"

  header "B3. Backend API endpoints (no token)"
  for ep in \
    "users|http://localhost:8089/api/v1/users?page=1&pageSize=1" \
    "authz/me|http://localhost:8089/api/v1/authz/me" \
    "themes|http://localhost:8091/api/v1/themes" \
    "companies|http://localhost:8092/api/v1/companies" \
    "reports|http://localhost:8095/api/v1/reports" \
    "schema-health|http://localhost:8096/actuator/health" \
    "audit|http://localhost:8089/api/audit/events" \
    "me-theme|http://localhost:8091/api/v1/me/theme/resolved" \
    "theme-reg|http://localhost:8091/api/v1/theme-registry"; do
    name="${ep%%|*}"
    url="${ep##*|}"
    code=$(curl -s -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || echo "000")
    [ "$code" = "200" ] && pass "API $name: $code" || fail "API $name: $code (200 olmalı)"
  done

  header "B4. Vite proxy endpoints"
  for ep in \
    "proxy-authz|http://localhost:3000/api/v1/authz/me" \
    "proxy-users|http://localhost:3000/api/v1/users?page=1&pageSize=1" \
    "proxy-reports|http://localhost:3000/api/v1/reports" \
    "proxy-companies|http://localhost:3000/api/v1/companies"; do
    name="${ep%%|*}"
    url="${ep##*|}"
    code=$(curl -s -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || echo "000")
    [ "$code" = "200" ] && pass "$name: $code" || warn "$name: $code (Vite çalışmıyor olabilir)"
  done

  header "B5. authz/me response — OpenFGA modules"
  AUTHZ_BODY=$(curl -sf "http://localhost:8089/api/v1/authz/me" 2>/dev/null || echo "{}")
  for module in USER_MANAGEMENT ACCESS AUDIT REPORT; do
    has=$(echo "$AUTHZ_BODY" | grep -c "$module" 2>/dev/null || true)
    has=${has:-0}
    [ "$has" -gt 0 ] && pass "authz/me: $module modülü var" || warn "authz/me: $module modülü yok"
  done

  header "B6. Keycloak health"
  KC_STATUS=$(curl -sf "http://localhost:8081/realms/serban" -o /dev/null -w "%{http_code}" 2>/dev/null || echo "000")
  [ "$KC_STATUS" = "200" ] && pass "Keycloak: UP (realm serban)" || warn "Keycloak: $KC_STATUS"

  header "B7. Permission-service container"
  PERM_RUNNING=$(docker ps --format "{{.Names}}" 2>/dev/null | grep -c "permission" || true)
  [ "$PERM_RUNNING" -eq 0 ] && pass "permission-service: NOT RUNNING (doğru)" || warn "permission-service: RUNNING (legacy modda olmalı)"

  header "B8. Service profiles"
  for svc in user-service variant-service core-data-service report-service; do
    container="serban-${svc}-1"
    profile=$(docker exec "$container" env 2>/dev/null | grep "SPRING_PROFILES_ACTIVE" | cut -d= -f2 || echo "UNREACHABLE")
    case "$profile" in
      *local*) pass "$svc: profile=$profile (local dahil)" ;;
      UNREACHABLE) warn "$svc: container erişilemedi" ;;
      *) warn "$svc: profile=$profile (local yok — JWT gerekebilir)" ;;
    esac
  done

fi

# ═══════════════════════════════════════════════════════════════════
# SONUÇ
# ═══════════════════════════════════════════════════════════════════
$JSON_MODE || echo ""
$JSON_MODE || echo "============================================"
if [ "$ERRORS" -eq 0 ]; then
  $JSON_MODE || green "  PASS: $CHECKS check, $ERRORS hata, $WARNINGS uyarı"
else
  $JSON_MODE || red "  FAIL: $CHECKS check, $ERRORS hata, $WARNINGS uyarı"
fi
$JSON_MODE || echo "============================================"

if $JSON_MODE; then
  echo "{\"status\":\"$([ "$ERRORS" -eq 0 ] && echo "pass" || echo "fail")\",\"checks\":$CHECKS,\"errors\":$ERRORS,\"warnings\":$WARNINGS}"
fi

exit "$ERRORS"
