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
# D-003 TRANSFORMED: permission-service is OpenFGA hub (not removed).
# Authz endpoints (/authz, /roles, /permissions) correctly route to 8090.
header "A7. Vite proxy — routing correctness"
VITE_CONFIG="$WEB_DIR/apps/mfe-shell/vite.config.ts"
if [ -f "$VITE_CONFIG" ]; then
  # D-003: 8090 is correct — permission-service is the OpenFGA hub
  has_authz_proxy=$(grep -v '^\s*//' "$VITE_CONFIG" | grep -c "api/v1/authz" 2>/dev/null || true)
  has_authz_proxy=${has_authz_proxy:-0}
  [ "$has_authz_proxy" -gt 0 ] && pass "vite: /api/v1/authz proxy tanımlı" || fail "vite: /api/v1/authz proxy eksik"

  # authz → 8090 (permission-service = OpenFGA hub, D-003 TRANSFORMED)
  authz_target=$(grep "api/v1/authz" "$VITE_CONFIG" | grep -o "localhost:[0-9]*" | head -1)
  [ "$authz_target" = "localhost:8090" ] && pass "vite: /api/v1/authz → 8090 (permission-service hub)" || fail "vite: /api/v1/authz → ${authz_target:-TANIMSIZ} (8090 olmalı — D-003)"

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

# ── A8. Frontend @mfe/auth usePermissions migration ────────────────
# Dalga 4: useAuthorization removed, usePermissions is the canonical hook.
header "A8. usePermissions — @mfe/auth canonical hook check"
AUTH_PKG_PROVIDER="$WEB_DIR/packages/auth/src/PermissionProvider.tsx"
if [ -f "$AUTH_PKG_PROVIDER" ]; then
  has_deny=$(grep -c "grant === 'ALLOW'" "$AUTH_PKG_PROVIDER" 2>/dev/null || true)
  has_deny=${has_deny:-0}
  [ "$has_deny" -gt 0 ] && pass "canViewReport: deny-default aktif" || fail "canViewReport: deny-default eksik"

  OLD_AUTH="$WEB_DIR/apps/mfe-shell/src/features/auth/model/use-authorization.model.ts"
  [ ! -f "$OLD_AUTH" ] && pass "legacy use-authorization.model.ts silinmiş (doğru)" || warn "legacy use-authorization.model.ts hala mevcut"
else
  fail "PermissionProvider.tsx bulunamadı"
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
# D-003 TRANSFORMED (CNS-20260411-001): permission-service is OpenFGA hub,
# NOT removed. It hosts TupleSyncService, AuthzVersionService, roles CRUD, /authz/me.
header "A12. Permission-service — must be ACTIVE as OpenFGA hub (D-003 TRANSFORMED)"
COMPOSE="$BACKEND_DIR/docker-compose.yml"
perm_exists=$(grep -c 'permission-service:' "$COMPOSE" 2>/dev/null || true)
perm_exists=${perm_exists:-0}
[ "$perm_exists" -gt 0 ] && pass "docker-compose: permission-service tanımlı (OpenFGA hub)" || fail "docker-compose: permission-service eksik — D-003 TRANSFORMED hub gerekli"

# ── A12b. Gateway routes — PERMISSION-SERVICE is the authz hub ──────
# D-003 TRANSFORMED: gateway routes for /authz, /roles, /permissions
# correctly point to PERMISSION-SERVICE (not USER-SERVICE).
header "A12b. Gateway routes — PERMISSION-SERVICE must route authz endpoints"
GW_PROPS="$BACKEND_DIR/api-gateway/src/main/resources/application.properties"
if [ -f "$GW_PROPS" ]; then
  # Find authz route index, then check its uri points to PERMISSION-SERVICE
  authz_idx=$(grep "authz" "$GW_PROPS" | grep -o 'routes\[[0-9]*\]' | head -1 | grep -o '[0-9]*')
  if [ -n "$authz_idx" ]; then
    authz_uri=$(grep "routes\[${authz_idx}\].uri" "$GW_PROPS" 2>/dev/null || true)
    echo "$authz_uri" | grep -q "PERMISSION-SERVICE" && pass "gateway: /authz → PERMISSION-SERVICE (D-003 hub)" || fail "gateway: /authz route ${authz_uri:-TANIMSIZ} (PERMISSION-SERVICE olmalı)"
  else
    fail "gateway: /authz route tanımı bulunamadı"
  fi
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
ADR_DIR="$BACKEND_DIR/../docs/02-architecture/services/ops/ADR"
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

# ── A18. Canary docs synced (CNS-20260414-003 Q6) ────────────────
# Fail-closed check: plan + runbook + registry counts must reflect shipped state.
# Source: Codex consultation CNS-20260414-003 recommendation
header "A18. Canary docs synced — plan/runbook/registry hizalamasi"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
PLAN_FILE="$REPO_ROOT/.claude/plans/zanzibar-master-plan.md"
RUNBOOK_FILE="$REPO_ROOT/docs/04-operations/RUNBOOKS/RB-zanzibar-canary.md"
REGISTRY_FILE="$REPO_ROOT/decisions/topics/zanzibar-openfga.v1.json"

if [ -f "$PLAN_FILE" ]; then
  # No "karar bekliyor" language for B1/B2
  karar_bekliyor=$(grep -c 'B[12].*karar bekliyor\|karar bekliyor.*B[12]' "$PLAN_FILE" 2>/dev/null || true)
  karar_bekliyor=${karar_bekliyor:-0}
  [ "$karar_bekliyor" -eq 0 ] && pass "plan: B1/B2 karar bekliyor metni yok" || fail "plan: B1/B2 karar bekliyor metni var ($karar_bekliyor)"

  # Plan count line must match registry (8/8/rev 4 after D-008)
  stale_count=$(grep -c '7 FINAL karar\|7 Constraint' "$PLAN_FILE" 2>/dev/null || true)
  stale_count=${stale_count:-0}
  [ "$stale_count" -eq 0 ] && pass "plan: sayim satiri guncel (7/7 drift yok)" || fail "plan: eski '7 FINAL / 7 Constraint' drift ($stale_count)"
else
  warn "plan dosyasi bulunamadi: $PLAN_FILE"
fi

if [ -f "$RUNBOOK_FILE" ]; then
  # Precondition "4/4 metrics" must be checked
  unchecked_4_4=$(grep -c '^- \[ \] Canary authz guardrail wiring complete (4/4 metrics)' "$RUNBOOK_FILE" 2>/dev/null || true)
  unchecked_4_4=${unchecked_4_4:-0}
  [ "$unchecked_4_4" -eq 0 ] && pass "runbook: '4/4 metrics' precondition [x]" || fail "runbook: '4/4 metrics' hala [ ] ($unchecked_4_4)"
else
  warn "runbook dosyasi bulunamadi: $RUNBOOK_FILE"
fi

if [ -f "$REGISTRY_FILE" ]; then
  reg_rev=$(grep -E '"revision"\s*:\s*[0-9]+' "$REGISTRY_FILE" | head -1 | grep -oE '[0-9]+' || echo "0")
  [ "$reg_rev" -ge 4 ] && pass "registry revision >= 4 (D-008 formalize)" || warn "registry revision=$reg_rev (beklenen >=4)"
fi

# ── A19. Auth-service legacy permission bootstrap (CNS-20260414-003 Q6) ──
# Warning-level drift guard for PR6a readiness.
# Source: Codex consultation CNS-20260414-003 Q6 + D-002 identity-only JWT
header "A19. Auth-service legacy permission bootstrap — PR6a drift guard"
AUTH_CLIENT="$BACKEND_DIR/auth-service/src/main/java/com/example/auth/permission/PermissionServiceClient.java"
AUTH_SERVICE="$BACKEND_DIR/auth-service/src/main/java/com/example/auth/service/AuthService.java"
JWT_PROVIDER="$BACKEND_DIR/auth-service/src/main/java/com/example/auth/security/JwtTokenProvider.java"

if [ -f "$AUTH_CLIENT" ]; then
  warn "auth-service: PermissionServiceClient.java hala mevcut (PR6a ile temizlenecek)"
else
  pass "auth-service: PermissionServiceClient.java yok (PR6a tamam)"
fi

if [ -f "$AUTH_SERVICE" ]; then
  psc_refs=$(grep -c 'permissionServiceClient\|PermissionServiceClient' "$AUTH_SERVICE" 2>/dev/null || true)
  psc_refs=${psc_refs:-0}
  if [ "$psc_refs" -gt 0 ]; then
    warn "AuthService.java: PermissionServiceClient $psc_refs referans (PR6a ile temizlenecek)"
  else
    pass "AuthService.java: PermissionServiceClient referans yok"
  fi
fi

if [ -f "$JWT_PROVIDER" ]; then
  jwt_perm_claim=$(grep -v '^\s*//' "$JWT_PROVIDER" | grep -c 'claim.*"permissions"\|"permissions".*claim\|setClaims.*permissions' 2>/dev/null || true)
  jwt_perm_claim=${jwt_perm_claim:-0}
  if [ "$jwt_perm_claim" -gt 0 ]; then
    warn "JwtTokenProvider: 'permissions' claim hala yaziliyor ($jwt_perm_claim ref, PR6b ile temizlenecek)"
  else
    pass "JwtTokenProvider: 'permissions' claim yazilmiyor"
  fi
fi

# ── A20. Permission-service "REMOVED" stale language drift guard ─────
# Non-legacy docs'ta agent'i yaniltabilecek eski "permission-service REMOVED" dili.
# D-003 TRANSFORMED (2026-04-11) sonrasi bu dil aspirasyonel kabul edildi.
# Agent ajanlar eski dili okuyup permission-service'i silerse Zanzibar bozulur (C-005).
# Muafiyet anahtarlari: OUTDATED, aspirasyon, TRANSFORMED, "Original D-003",
# "was changed", "REMOVED" tut/label (acıklayıcı baglam).
header "A20. 'permission-service REMOVED' stale language drift guard (non-legacy docs)"
DOCS_ROOT="$BACKEND_DIR/.."
SCAN_PATHS=(
  "$DOCS_ROOT/docs/02-architecture"
  "$DOCS_ROOT/docs/04-operations"
  "$DOCS_ROOT/docs/03-delivery"
  "$DOCS_ROOT/.claude/rules"
)
STALE_FILES=()
for scan_path in "${SCAN_PATHS[@]}"; do
  [ -d "$scan_path" ] || continue
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    # Dosya aciklayici baglam iceriyorsa muaf
    if ! grep -qE 'OUTDATED|aspirasyon|TRANSFORMED|Original D-003|was changed|REMOVED.{0,15}(tut|label|etiketin|claim|language)|kaldırılamaz|kaldırılmamış' "$f" 2>/dev/null; then
      STALE_FILES+=("${f#$DOCS_ROOT/}")
    fi
  done < <(grep -rlE 'permission-service[^"]{0,60}REMOVED' "$scan_path" 2>/dev/null)
done
if [ ${#STALE_FILES[@]} -eq 0 ]; then
  pass "A20: non-legacy docs'ta 'permission-service REMOVED' stale dili yok"
else
  for sf in "${STALE_FILES[@]}"; do
    fail "A20: $sf — 'permission-service REMOVED' stale dili (D-003 ihlali riski)"
  done
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

  header "B7. Permission-service container (D-003: OpenFGA hub)"
  PERM_RUNNING=$(docker ps --format "{{.Names}}" 2>/dev/null | grep -c "permission" || true)
  [ "$PERM_RUNNING" -ge 1 ] && pass "permission-service: RUNNING (D-003 OpenFGA hub)" || warn "permission-service: NOT RUNNING (hub gerekli — D-003)"

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
# SECTION C: COMPOSE TUTARLILIK (code-level, Docker gerekmez)
# ═══════════════════════════════════════════════════════════════════
$JSON_MODE || echo ""
$JSON_MODE || echo "── Compose Tutarlılık ──"

# C1: dev compose name=platform
DEV_NAME=$(grep -m1 '^name:' "$BACKEND_DIR/docker-compose.yml" 2>/dev/null | awk '{print $2}')
if [[ "$DEV_NAME" == "platform" ]]; then
  pass "C1" "docker-compose.yml name=platform"
else
  fail "C1" "docker-compose.yml name='$DEV_NAME' (beklenen: platform)"
fi

# C2: prod compose name=platform (hardcoded, not variable)
PROD_NAME=$(grep -m1 '^name:' "$BACKEND_DIR/../deploy/docker-compose.prod.yml" 2>/dev/null | awk '{print $2}')
if [[ "$PROD_NAME" == "platform" ]]; then
  pass "C2" "../deploy/docker-compose.prod.yml name=platform"
elif [[ "$PROD_NAME" == *'$'* ]]; then
  fail "C2" "../deploy/docker-compose.prod.yml name değişken ($PROD_NAME) — hardcoded 'platform' olmalı"
else
  fail "C2" "../deploy/docker-compose.prod.yml name='$PROD_NAME' (beklenen: platform)"
fi

# C3: No stray container_name (except vault)
CUSTOM_NAMES=$(grep "container_name:" "$BACKEND_DIR/docker-compose.yml" 2>/dev/null | grep -v vault | grep -v "^#" | grep -v "^[[:space:]]*#" || true)
if [[ -z "$CUSTOM_NAMES" ]]; then
  pass "C3" "container_name override yok (vault hariç)"
else
  warn "C3" "container_name override: $CUSTOM_NAMES"
fi

if ! $QUICK_MODE; then
  $JSON_MODE || echo ""
  $JSON_MODE || echo "── Compose Runtime ──"

  # C4: Single compose project
  PROJECTS=$(docker ps --format "{{.Labels}}" 2>/dev/null | grep -oP "com\.docker\.compose\.project=\K[^,]+" | sort -u)
  if [[ "$PROJECTS" == "platform" ]]; then
    pass "C4: Tek compose project: platform"
  else
    fail "C4: Projeler: $PROJECTS (tek 'platform' olmalı)"
  fi

  # C5: All containers from same compose file
  CONFIG_FILES=$(docker ps --format "{{.Labels}}" 2>/dev/null | grep -oP "com\.docker\.compose\.project\.config_files=\K[^,]+" | sort -u | head -5)
  CONFIG_COUNT=$(echo "$CONFIG_FILES" | grep -c . 2>/dev/null || echo 0)
  if [[ "$CONFIG_COUNT" -le 1 ]]; then
    pass "C5: Tüm container'lar aynı compose dosyasından"
  else
    fail "C5: $CONFIG_COUNT farklı compose dosyası"
  fi

  # C6: Spring profiles consistent (not prod for local-mode services)
  PROD_PROFILE_SVCS=""
  for SVC_CONTAINER in platform-report-service-1 platform-schema-service-1 platform-permission-service-1; do
    PROF=$(docker exec "$SVC_CONTAINER" printenv SPRING_PROFILES_ACTIVE 2>/dev/null || echo "?")
    if [[ "$PROF" == "prod" ]]; then
      PROD_PROFILE_SVCS="$PROD_PROFILE_SVCS $SVC_CONTAINER"
    fi
  done
  if [[ -z "$PROD_PROFILE_SVCS" ]]; then
    pass "C6: Profil tutarlı (prod profilde çalışan yok)"
  else
    fail "C6: Prod profilde çalışıyor:$PROD_PROFILE_SVCS"
  fi

  # C7: Eureka — critical services registered with container IP
  BAD_EUREKA=""
  for APP in PERMISSION-SERVICE REPORT-SERVICE; do
    HOST=$(curl -s -H "Accept: application/json" "http://127.0.0.1:8761/eureka/apps/$APP" 2>/dev/null \
      | python3 -c "import sys,json
try:
  d=json.load(sys.stdin);i=d['application']['instance']
  i=i[0] if isinstance(i,list) else i;print(i.get('hostName','?'))
except: print('?')" 2>/dev/null)
    if [[ "$HOST" == "127.0.0.1" ]]; then
      BAD_EUREKA="$BAD_EUREKA $APP"
    fi
  done
  if [[ -z "$BAD_EUREKA" ]]; then
    pass "C7: Eureka IP doğru (container IP kullanılıyor)"
  else
    fail "C7: Eureka 127.0.0.1 kayıtlı:$BAD_EUREKA"
  fi
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
