#!/usr/bin/env bash
set -euo pipefail
# doctor-infra.sh — Infrastructure consistency checker
# Validates port mappings, config alignment, and known failure patterns.
# Exit 0 = PASS, Exit 1 = FAIL
# Usage: bash scripts/doctor-infra.sh [--quick]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.yml"
DEPLOY_SCRIPT="${ROOT_DIR}/../deploy/ubuntu/run-frontend-nginx-container.sh"
VITE_CONFIG="${ROOT_DIR}/../web/apps/mfe-shell/vite.config.ts"
ENV_EXAMPLE="${ROOT_DIR}/.env.example"
FAILURES=0
WARNINGS=0
CHECKS=0

pass() { CHECKS=$((CHECKS+1)); echo "  [PASS] $1"; }
fail() { CHECKS=$((CHECKS+1)); FAILURES=$((FAILURES+1)); echo "  [FAIL] $1"; }
warn() { CHECKS=$((CHECKS+1)); WARNINGS=$((WARNINGS+1)); echo "  [WARN] $1"; }

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  doctor-infra.sh — Infrastructure Consistency Check          ║"
echo "╚══════════════════════════════════════════════════════════════╝"

# ── A: NGINX DEPLOY SCRIPT DEFAULTS ──────────────────────────────────
echo ""
echo "=== A: Nginx Deploy Script (host network mode) ==="

if [ -f "$DEPLOY_SCRIPT" ]; then
  # A1: Gateway upstream must use 127.0.0.1, not Docker hostname
  if grep -q 'NGINX_GATEWAY_UPSTREAM.*http://127.0.0.1' "$DEPLOY_SCRIPT"; then
    pass "A1: Gateway upstream default → 127.0.0.1"
  elif grep -q 'NGINX_GATEWAY_UPSTREAM.*api-gateway' "$DEPLOY_SCRIPT"; then
    fail "A1: Gateway upstream uses Docker hostname 'api-gateway' — won't resolve in host network"
  else
    warn "A1: Gateway upstream pattern not found"
  fi

  # A2: Service-manager upstream must use 127.0.0.1
  if grep -q 'NGINX_SERVICE_MANAGER_UPSTREAM.*http://127.0.0.1' "$DEPLOY_SCRIPT"; then
    pass "A2: Service-manager upstream default → 127.0.0.1"
  elif grep -q 'NGINX_SERVICE_MANAGER_UPSTREAM.*service-manager' "$DEPLOY_SCRIPT"; then
    fail "A2: Service-manager upstream uses Docker hostname — won't resolve in host network"
  else
    warn "A2: Service-manager upstream pattern not found"
  fi

  # A3: Keycloak upstream must use host port 8081
  if grep -q 'NGINX_KEYCLOAK_UPSTREAM.*127.0.0.1:8081' "$DEPLOY_SCRIPT"; then
    pass "A3: Keycloak upstream → 127.0.0.1:8081 (host port)"
  elif grep -q 'NGINX_KEYCLOAK_UPSTREAM.*:8080' "$DEPLOY_SCRIPT"; then
    fail "A3: Keycloak upstream uses container port 8080 instead of host port 8081"
  else
    warn "A3: Keycloak upstream pattern not found"
  fi

  # A4: NGINX_TLS_CERT_PATH default must NOT point to Vault TLS directory
  # (2026-04-14 regression: Vault's dev self-signed cert CN=vault was served).
  if grep -qE 'NGINX_TLS_CERT_PATH.*[:-].*/vault/tls/' "$DEPLOY_SCRIPT"; then
    fail "A4: NGINX_TLS_CERT_PATH default points to Vault TLS dir — will serve CN=vault cert (2026-04-14 regression)"
  elif grep -qE 'NGINX_TLS_CERT_PATH.*[:-].*' "$DEPLOY_SCRIPT"; then
    pass "A4: NGINX_TLS_CERT_PATH default not pointing to Vault TLS dir"
  else
    warn "A4: NGINX_TLS_CERT_PATH default not found in deploy script"
  fi

  # A5: Cert pre-flight guard must be present (CN=vault rejection + CN/SAN match)
  if grep -q "NGINX_SKIP_CERT_GUARD\|CN\\\\s\\*=\\\\s\\*vault" "$DEPLOY_SCRIPT"; then
    pass "A5: Cert pre-flight guard present (rejects CN=vault, verifies CN/SAN)"
  else
    fail "A5: Cert pre-flight guard missing — deploy may silently serve wrong cert"
  fi
else
  warn "A0: Deploy script not found at $DEPLOY_SCRIPT"
fi

# A6: Runtime cert served on nginx matches server_name (requires running container)
# Fail-closed if container running; skip if not (deploy-time static checks A4/A5 cover defaults)
if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^platform-web-nginx$"; then
  # Prefer host openssl; skip gracefully if missing
  if command -v openssl >/dev/null 2>&1; then
    cert_sub=$(echo | openssl s_client -servername ai.acik.com -connect localhost:443 2>/dev/null \
               | openssl x509 -noout -subject 2>/dev/null || true)
    if echo "$cert_sub" | grep -qE 'CN\s*=\s*vault'; then
      fail "A6: Nginx (running) serves Vault's CN=vault self-signed cert — 2026-04-14 regression active"
    elif echo "$cert_sub" | grep -qE 'CN\s*=\s*\*?\.?(acik\.com|ai\.acik\.com)'; then
      pass "A6: Nginx (running) serves a cert covering ai.acik.com"
    else
      warn "A6: Nginx cert subject unrecognized: $cert_sub"
    fi
  else
    warn "A6: openssl not available on host — runtime cert check skipped"
  fi
fi

# ── B: DOCKER COMPOSE CONSISTENCY ────────────────────────────────────
echo ""
echo "=== B: Docker Compose Configuration ==="

if [ -f "$COMPOSE_FILE" ]; then
  # B1: All OpenFGA URLs must be consistent (same port)
  openfga_ports=$(grep "ERP_OPENFGA_API_URL" "$COMPOSE_FILE" | grep -oE ':[0-9]+' | sort -u)
  if [ "$(echo "$openfga_ports" | wc -l)" -eq 1 ]; then
    pass "B1: OpenFGA URLs consistent (all$(echo $openfga_ports))"
  else
    fail "B1: OpenFGA URL port mismatch: $(echo $openfga_ports | tr '\n' ' ')"
  fi

  # B2: Keycloak must have KC_DB=postgres (no H2)
  if grep -q "KC_DB=postgres" "$COMPOSE_FILE"; then
    pass "B2: Keycloak uses PostgreSQL backend"
  else
    fail "B2: Keycloak missing KC_DB=postgres — H2 data loss risk"
  fi

  # B3: Keycloak must have KC_HEALTH_ENABLED
  if grep -q "KC_HEALTH_ENABLED=true" "$COMPOSE_FILE"; then
    pass "B3: Keycloak health endpoint enabled"
  else
    fail "B3: KC_HEALTH_ENABLED not set — healthcheck will fail"
  fi

  # B4: Keycloak healthcheck must use port 9000 (management) — OpenFGA has no healthcheck (minimal image)
  if grep -A3 "keycloak" "$COMPOSE_FILE" | grep -q "9000"; then
    pass "B4: Keycloak healthcheck targets port 9000"
  else
    warn "B4: Keycloak healthcheck may not target management port 9000"
  fi

  # B5: OpenFGA depends_on openfga-migrate (service_completed_successfully)
  if grep -B2 'service_completed_successfully' "$COMPOSE_FILE" | grep -q 'openfga-migrate'; then
    pass "B5: OpenFGA depends_on openfga-migrate"
  else
    fail "B5: OpenFGA missing depends_on openfga-migrate — race condition"
  fi

  # B6: service-manager has start_period
  if sed -n "/service-manager/,/^  [a-z]/p" "$COMPOSE_FILE" | grep -q "start_period"; then
    pass "B6: service-manager has start_period"
  else
    warn "B6: service-manager missing start_period — may show unhealthy during boot"
  fi

  # B7: No duplicate compose project names
  compose_names=$(grep "^name:" "$COMPOSE_FILE" | awk '{print $2}')
  if [ "$compose_names" = "platform" ]; then
    pass "B7: Compose project name = platform"
  else
    fail "B7: Compose project name unexpected: $compose_names"
  fi
else
  fail "B0: docker-compose.yml not found"
fi

# ── C: EUREKA INSTANCE CONFIG (docker profile) ──────────────────────
echo ""
echo "=== C: Eureka Docker Profile Consistency ==="

for svc in api-gateway auth-service core-data-service permission-service report-service schema-service user-service variant-service; do
  docker_props="${ROOT_DIR}/${svc}/src/main/resources/application-docker.properties"
  if [ -f "$docker_props" ]; then
    # C1: prefer-ip-address=true
    if grep -q "eureka.instance.prefer-ip-address=true" "$docker_props"; then
      pass "C1-${svc}: prefer-ip-address=true"
    else
      fail "C1-${svc}: Missing prefer-ip-address=true — will register as 127.0.0.1"
    fi
    # C2: eureka client URL uses discovery-server (not localhost)
    if grep -q "eureka.client.*defaultZone.*discovery-server" "$docker_props"; then
      pass "C2-${svc}: Eureka client → discovery-server"
    elif grep -q "eureka.client.*defaultZone.*localhost" "$docker_props"; then
      fail "C2-${svc}: Eureka client → localhost — won't resolve in container"
    else
      warn "C2-${svc}: No explicit Eureka client URL in docker profile"
    fi
  else
    fail "C0-${svc}: Missing application-docker.properties"
  fi
done

# ── D: KNOWN FAILURE PATTERNS ────────────────────────────────────────
echo ""
echo "=== D: Known Failure Pattern Detection ==="

# D1: PERMISSIONS constant still referenced (caused white screen)
if grep -rn "\bPERMISSIONS\b" "${ROOT_DIR}/../web/apps/mfe-shell/src/" --include="*.ts" --include="*.tsx" 2>/dev/null | grep -v "removed\|Legacy\|deprecated\|comment\|\.test\.\|\.spec\." | grep -q .; then
  fail "D1: Stale PERMISSIONS constant reference found in shell (white screen risk)"
else
  pass "D1: No stale PERMISSIONS references"
fi

# D2: KC_HOSTNAME default should not be localhost for staging
kc_default=$(grep "KC_HOSTNAME" "$COMPOSE_FILE" | grep -oE ':-[^}]+' | head -1)
if echo "$kc_default" | grep -q "localhost"; then
  warn "D2: KC_HOSTNAME default is localhost — must override in .env for staging"
else
  pass "D2: KC_HOSTNAME default is not localhost"
fi

# D3: .env.example documents KC_HOSTNAME
if [ -f "$ENV_EXAMPLE" ] && grep -q "KC_HOSTNAME" "$ENV_EXAMPLE"; then
  pass "D3: .env.example documents KC_HOSTNAME"
else
  fail "D3: .env.example missing KC_HOSTNAME documentation"
fi


# ── E: COMPOSE FILE CONFLICT DETECTION ───────────────────────────────
echo ""
echo "=== E: Compose File & Service Conflict Detection ==="

# E1: Multiple compose files with overlapping services
COMPOSE_FILES=$(find "${ROOT_DIR}" -maxdepth 1 -name "docker-compose*.yml" -o -name "docker-compose*.yaml" 2>/dev/null)
compose_count=$(echo "$COMPOSE_FILES" | wc -l | tr -d ' ')
if [ "$compose_count" -gt 1 ]; then
  # Check for duplicate service definitions across files
  all_services=""
  for cf in $COMPOSE_FILES; do
    services=$(grep -E "^  [a-z].*:" "$cf" 2>/dev/null | sed 's/:.*//' | tr -d ' ' | sort)
    all_services="${all_services}\n${services}"
  done
  dupes=$(echo -e "$all_services" | sort | uniq -d | grep -v "^$" || true)
  if [ -n "$dupes" ]; then
    warn "E1: Overlapping services across compose files (dev/prod expected): $(echo $dupes | tr '\n' ' ')"
  else
    pass "E1: No duplicate services across $compose_count compose files"
  fi
else
  pass "E1: Single compose file (no overlap risk)"
fi

# E2: All compose files must use same project name
project_names=""
for cf in $COMPOSE_FILES; do
  pname=$(grep "^name:" "$cf" 2>/dev/null | awk '{print $2}' || true)
  if [ -n "$pname" ]; then
    project_names="${project_names} ${pname}"
  fi
done
unique_names=$(echo "$project_names" | tr ' ' '\n' | sort -u | grep -v "^$" | wc -l | tr -d ' ')
if [ "$unique_names" -gt 1 ]; then
  fail "E2: Multiple compose project names:$project_names — orphan containers will occur"
elif [ "$unique_names" -eq 1 ]; then
  pass "E2: All compose files use same project name:$project_names"
else
  warn "E2: No compose project name found (Docker default will be used)"
fi

# E3: No prod compose file should be used on staging (check for conflicting profiles)
PROD_COMPOSE="${ROOT_DIR}/../deploy/docker-compose.prod.yml"
if [ -f "$PROD_COMPOSE" ]; then
  # Check if prod compose has different port mappings than dev
  prod_ports=$(grep -E "^\s+- \"[0-9]+:" "$PROD_COMPOSE" 2>/dev/null | sort || true)
  dev_ports=$(grep -E "^\s+- \"[0-9]+:" "$COMPOSE_FILE" 2>/dev/null | sort || true)
  conflicts=$(comm -3 <(echo "$prod_ports") <(echo "$dev_ports") 2>/dev/null | head -5 || true)
  if [ -n "$conflicts" ]; then
    warn "E3: docker-compose.prod.yml has different port mappings than dev — ensure correct file is used"
  else
    pass "E3: Prod and dev compose port mappings compatible"
  fi
else
  pass "E3: No prod compose file (single compose approach)"
fi

# E4: COMPOSE_PROJECT_NAME in .env.example
if [ -f "$ENV_EXAMPLE" ] && grep -q "COMPOSE_PROJECT_NAME" "$ENV_EXAMPLE"; then
  pass "E4: .env.example documents COMPOSE_PROJECT_NAME"
else
  warn "E4: .env.example missing COMPOSE_PROJECT_NAME — risk of default name"
fi

# ── F: VAULT AUTO-UNSEAL READINESS ──────────────────────────────────
echo ""
echo "=== F: Vault Auto-Unseal ==="

# F1: vault-unseal container defined in compose
if grep -q "vault-unseal" "$COMPOSE_FILE"; then
  pass "F1: vault-unseal service defined"
else
  fail "F1: vault-unseal service missing — manual unseal required on every restart"
fi

# F2: dev-unseal-loop.sh exists
UNSEAL_SCRIPT="${ROOT_DIR}/devops/vault/dev-unseal-loop.sh"
if [ -f "$UNSEAL_SCRIPT" ]; then
  pass "F2: dev-unseal-loop.sh exists"
else
  fail "F2: dev-unseal-loop.sh missing"
fi

# F3: .vault-dev directory exists
VAULT_DEV_DIR="${ROOT_DIR}/.vault-dev"
if [ -d "$VAULT_DEV_DIR" ]; then
  pass "F3: .vault-dev directory exists"
  if [ -s "${VAULT_DEV_DIR}/vault-unseal-key" ]; then
    pass "F4: vault-unseal-key file present"
  else
    warn "F4: vault-unseal-key missing — vault needs manual unseal after restart"
  fi
else
  warn "F3: .vault-dev directory missing — run vault init first"
fi

# F5: Keycloak depends_on postgres-db
if grep -A5 "keycloak:" "$COMPOSE_FILE" | grep -q "postgres-db"; then
  pass "F5: Keycloak depends_on postgres-db"
else
  fail "F5: Keycloak missing postgres-db dependency"
fi

# ── G: COMPOSE SINGLE-INSTANCE GUARD ────────────────────────────────
echo ""
echo "=== G: Single Instance Guard ==="

# G1: Only one compose file in backend/ root (prod moved to deploy/)
COMPOSE_COUNT=$(find "${ROOT_DIR}" -maxdepth 1 -name "docker-compose*.yml" -o -name "docker-compose*.yaml" 2>/dev/null | wc -l | tr -d " ")
if [ "$COMPOSE_COUNT" -eq 1 ]; then
  pass "G1: Single compose file in backend/ ($COMPOSE_COUNT)"
elif [ "$COMPOSE_COUNT" -eq 0 ]; then
  fail "G1: No compose file found in backend/"
else
  fail "G1: Multiple compose files in backend/ ($COMPOSE_COUNT) — prod should be in deploy/"
fi

# G2: docker-compose.prod.yml NOT in backend/ (must be in deploy/)
if [ -f "${ROOT_DIR}/docker-compose.prod.yml" ]; then
  fail "G2: docker-compose.prod.yml still in backend/ — must be in deploy/"
else
  pass "G2: docker-compose.prod.yml not in backend/ (correct)"
fi

# G3: Compose project name consistency
if grep -q "^name: platform" "$COMPOSE_FILE" 2>/dev/null; then
  pass "G3: Compose project name = platform"
else
  fail "G3: Compose project name missing or incorrect"
fi

# ── H: DEV/PROD COMPOSE SYNC CHECK ──────────────────────────────────
echo ""
echo "=== H: Dev/Prod Compose Sync ==="

PROD_COMPOSE="${ROOT_DIR}/../deploy/docker-compose.prod.yml"
if [ ! -f "$PROD_COMPOSE" ]; then
  warn "H0: docker-compose.prod.yml not found at deploy/"
else
  # H1: Same services
  DEV_SVCS=$(grep -E "^  [a-z][a-z0-9_-]+:" "$COMPOSE_FILE" | sed 's/:.*//' | tr -d ' ' | sort)
  PROD_SVCS=$(grep -E "^  [a-z][a-z0-9_-]+:" "$PROD_COMPOSE" | sed 's/:.*//' | tr -d ' ' | sort)
  MISSING_IN_PROD=$(comm -23 <(echo "$DEV_SVCS") <(echo "$PROD_SVCS") | tr '\n' ' ')
  MISSING_IN_DEV=$(comm -13 <(echo "$DEV_SVCS") <(echo "$PROD_SVCS") | tr '\n' ' ')
  if [ -z "$(echo "$MISSING_IN_PROD" | tr -d ' ')" ] && [ -z "$(echo "$MISSING_IN_DEV" | tr -d ' ')" ]; then
    pass "H1: Dev and prod have identical service sets"
  else
    [ -n "$(echo "$MISSING_IN_PROD" | tr -d ' ')" ] && warn "H1a: In dev not prod: $MISSING_IN_PROD"
    [ -n "$(echo "$MISSING_IN_DEV" | tr -d ' ')" ] && warn "H1b: In prod not dev: $MISSING_IN_DEV"
  fi

  # H2: Same project name
  DEV_NAME=$(grep '^name:' "$COMPOSE_FILE" | awk '{print $2}')
  PROD_NAME=$(grep '^name:' "$PROD_COMPOSE" | awk '{print $2}')
  if [ "$DEV_NAME" = "$PROD_NAME" ]; then
    pass "H2: Same project name ($DEV_NAME)"
  else
    fail "H2: Project name mismatch: dev=$DEV_NAME prod=$PROD_NAME"
  fi

  # H3: Keycloak PG in prod
  if grep -q 'KC_DB.*postgres' "$PROD_COMPOSE"; then
    pass "H3: Prod Keycloak uses PostgreSQL"
  else
    fail "H3: Prod Keycloak missing KC_DB=postgres"
  fi

  # H4: KC_HEALTH_ENABLED in prod
  if grep -q 'KC_HEALTH_ENABLED' "$PROD_COMPOSE"; then
    pass "H4: Prod KC_HEALTH_ENABLED set"
  else
    fail "H4: Prod Keycloak missing KC_HEALTH_ENABLED"
  fi

  # H5: Healthcheck port 9000 in prod
  if grep -A5 'keycloak' "$PROD_COMPOSE" | grep -q '9000'; then
    pass "H5: Prod Keycloak healthcheck port 9000"
  else
    fail "H5: Prod Keycloak healthcheck not on port 9000"
  fi

  # H6: OpenFGA playground off in prod
  if grep -q 'OPENFGA_PLAYGROUND_ENABLED.*false' "$PROD_COMPOSE"; then
    pass "H6: Prod OpenFGA playground disabled"
  elif grep -q 'OPENFGA_PLAYGROUND_ENABLED.*true' "$PROD_COMPOSE"; then
    fail "H6: Prod OpenFGA playground ENABLED"
  else
    pass "H6: Prod OpenFGA playground default (off)"
  fi

  # H7: Prod ports bind 127.0.0.1
  OPEN_PORTS=$(grep -E '^\s+- "[0-9]+:' "$PROD_COMPOSE" | grep -v '127.0.0.1' | head -3 || true)
  if [ -z "$OPEN_PORTS" ]; then
    pass "H7: All prod ports bind 127.0.0.1"
  else
    fail "H7: Prod ports exposed to 0.0.0.0"
  fi
fi

# ── I: ENVIRONMENT & COMPOSE SELECTION GUARD ─────────────────────────
echo ""
echo "=== I: Environment & Compose Selection ==="

# I1: Dev compose uses local build (not registry)
if grep -q "build:" "$COMPOSE_FILE" && ! grep -q "ghcr.io" "$COMPOSE_FILE"; then
  pass "I1: Dev compose uses local build (not registry)"
elif grep -q "ghcr.io" "$COMPOSE_FILE"; then
  fail "I1: Dev compose references GHCR registry"
else
  warn "I1: Dev compose has no build or image directives"
fi

# I2: Prod compose uses registry images
if [ -f "$PROD_COMPOSE" ]; then
  if grep -q "ghcr.io" "$PROD_COMPOSE" && ! grep -q "build:" "$PROD_COMPOSE"; then
    pass "I2: Prod compose uses GHCR registry images"
  elif grep -q "build:" "$PROD_COMPOSE"; then
    fail "I2: Prod compose uses local build — must use GHCR"
  else
    warn "I2: Prod compose image source unclear"
  fi
else
  warn "I2: Prod compose not found"
fi

# I3: Dev profiles include local
if grep "SPRING_PROFILES_ACTIVE" "$COMPOSE_FILE" | grep -q "local"; then
  pass "I3: Dev compose profiles include local"
else
  fail "I3: Dev compose profiles missing local"
fi

# I4: Prod profiles = prod
if [ -f "$PROD_COMPOSE" ] && grep "SPRING_PROFILES_ACTIVE" "$PROD_COMPOSE" | grep -q "prod"; then
  pass "I4: Prod compose profiles = prod"
elif [ -f "$PROD_COMPOSE" ]; then
  fail "I4: Prod compose profiles not prod"
fi

# I5: No accidental prod compose in .env
if grep -q "COMPOSE_FILE.*prod" "${ROOT_DIR}/.env" 2>/dev/null; then
  fail "I5: COMPOSE_FILE in .env points to prod"
else
  pass "I5: No prod compose override in .env"
fi

# ============================================================================
# Section K: Compose Volume SSOT (staging ↔ prod must define the same volumes
# with the same Docker-level names). Drift causes fresh-volume incidents when
# deploy-backend.sh switches between compose files (2026-04-14 root cause).
#
# K1: backend & prod compose top-level volume keys are identical
# K2: each volume has an explicit `name: platform_*` override (Docker-level
#     pin; immune to compose project name changes or file-origin drift)
# K3: no stale volume declarations (declared but no service mounts it)
# K4: no dash/underscore naming drift (vault-data vs vault_data etc.)
# ============================================================================
STAGING_COMPOSE="${ROOT_DIR}/docker-compose.yml"
PROD_COMPOSE="${ROOT_DIR}/../deploy/docker-compose.prod.yml"

# Extract top-level volume keys (first-level under "volumes:" block).
extract_volume_keys() {
  local file="$1"
  [ -f "$file" ] || return 0
  awk '
    /^volumes:/ { in_vol=1; next }
    in_vol && /^[a-z]/ { in_vol=0 }
    in_vol && /^  [a-z_][a-z0-9_-]*:$/ {
      gsub(/[: ]/, "", $0); print
    }
  ' "$file" | sort -u
}

# Extract explicit `name:` overrides under each volume key.
extract_volume_names() {
  local file="$1"
  [ -f "$file" ] || return 0
  awk '
    /^volumes:/ { in_vol=1; next }
    in_vol && /^[a-z]/ { in_vol=0 }
    in_vol && /^    name:/ {
      gsub(/^.*name:[ ]*/, "", $0); print
    }
  ' "$file" | sort -u
}

if [ -f "$STAGING_COMPOSE" ] && [ -f "$PROD_COMPOSE" ]; then
  STAGING_KEYS="$(extract_volume_keys "$STAGING_COMPOSE")"
  PROD_KEYS="$(extract_volume_keys "$PROD_COMPOSE")"
  STAGING_NAMES="$(extract_volume_names "$STAGING_COMPOSE")"
  PROD_NAMES="$(extract_volume_names "$PROD_COMPOSE")"

  # K1: identical top-level keys
  if [ "$STAGING_KEYS" = "$PROD_KEYS" ]; then
    pass "K1: Compose volume keys identical across staging↔prod"
  else
    fail "K1: Compose volume key drift (staging↔prod mismatch — see 'diff <(keys staging) <(keys prod)')"
  fi

  # K2: every key has explicit name: override
  K2_MISSING=""
  for keys_file in "$STAGING_COMPOSE" "$PROD_COMPOSE"; do
    k_count=$(extract_volume_keys "$keys_file" | wc -l | tr -d ' ')
    n_count=$(extract_volume_names "$keys_file" | wc -l | tr -d ' ')
    if [ "$k_count" != "$n_count" ]; then
      K2_MISSING="$K2_MISSING ${keys_file##*/}($k_count keys vs $n_count names)"
    fi
  done
  if [ -z "$K2_MISSING" ]; then
    pass "K2: Every volume has explicit name: override (Docker-level pin)"
  else
    fail "K2: Volumes missing explicit name: override —$K2_MISSING"
  fi

  # K3: no stale declarations — every declared volume is mounted by some service
  K3_STALE=""
  for key in $STAGING_KEYS; do
    if ! grep -qE "^\s+- ${key}:/" "$STAGING_COMPOSE"; then
      # Also check vault_logs/vault_snapshots which are optional on staging
      if [ "$key" != "vault_logs" ] && [ "$key" != "vault_snapshots" ]; then
        K3_STALE="$K3_STALE staging:$key"
      fi
    fi
  done
  for key in $PROD_KEYS; do
    if ! grep -qE "^\s+- ${key}:/" "$PROD_COMPOSE"; then
      K3_STALE="$K3_STALE prod:$key"
    fi
  done
  if [ -z "$K3_STALE" ]; then
    pass "K3: No stale volume declarations"
  else
    fail "K3: Stale volume declarations (no service mounts them):$K3_STALE"
  fi

  # K4: no dash-vs-underscore drift in volume keys
  K4_DRIFT=""
  for file in "$STAGING_COMPOSE" "$PROD_COMPOSE"; do
    if extract_volume_keys "$file" | grep -q '-'; then
      K4_DRIFT="$K4_DRIFT ${file##*/}"
    fi
  done
  if [ -z "$K4_DRIFT" ]; then
    pass "K4: No dash-in-key naming drift (underscore-only)"
  else
    fail "K4: Dash in volume key (use underscore):$K4_DRIFT"
  fi
else
  warn "K: Compose file(s) missing — SSOT check skipped"
fi

# ── L: SPRING PROFILES STAGING GUARD (STORY-0319) ───────────────────
# Staging'de SPRING_PROFILES_ACTIVE içinde 'local' varsa canary metrics
# anlamsız — SecurityConfigLocal permitAll aktif olur.
# Runtime container env'i kontrol edilir (Docker exec).
echo ""
echo "=== L: Spring Profiles Staging Guard (STORY-0319) ==="

SERVICES_TO_CHECK="user-service auth-service variant-service core-data-service api-gateway permission-service report-service"
for svc in $SERVICES_TO_CHECK; do
  CONTAINER="platform-${svc}-1"
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${CONTAINER}$"; then
    ACTIVE=$(docker exec "$CONTAINER" printenv SPRING_PROFILES_ACTIVE 2>/dev/null || echo "N/A")
    if echo "$ACTIVE" | grep -qi "local"; then
      fail "L1-${svc}: SPRING_PROFILES_ACTIVE='${ACTIVE}' hâlâ 'local' içeriyor (canary metrics anlamsız)"
    else
      pass "L1-${svc}: '${ACTIVE}' (local yok)"
    fi
  else
    warn "L1-${svc}: container '${CONTAINER}' çalışmıyor (skip)"
  fi
done

# L2: Token'sız /authz/check 401/403 doğrulaması (prod profile = JWT zorunlu)
# CNS-20260416-003 Codex fix: -sf flag'i kaldırıldı. -f ile 4xx status'ta curl exit 22
# döner, || echo "000" tetiklenir, gerçek HTTP status (401/403) yakalanmaz.
# Doğru pattern: -s -o /dev/null -w '%{http_code}' → her durumda status code döner.
AUTHZ_CHECK_URL="${AUTHZ_CHECK_URL:-http://localhost:8090/api/v1/authz/check}"
AUTHZ_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$AUTHZ_CHECK_URL" \
  -H "Content-Type: application/json" \
  -d '{"relation":"can_view","objectType":"module","objectId":"TEST"}' 2>/dev/null || echo "000")
if [ "$AUTHZ_STATUS" = "401" ] || [ "$AUTHZ_STATUS" = "403" ]; then
  pass "L2: Token'sız /authz/check → ${AUTHZ_STATUS} (JWT zorunlu, permitAll kapalı)"
elif [ "$AUTHZ_STATUS" = "200" ]; then
  fail "L2: Token'sız /authz/check → 200 (SecurityConfigLocal permitAll hâlâ aktif!)"
elif [ "$AUTHZ_STATUS" = "000" ]; then
  warn "L2: /authz/check ulaşılamadı (permission-service down veya network?)"
else
  warn "L2: Token'sız /authz/check → ${AUTHZ_STATUS} (beklenmeyen — 401/403 bekleniyor)"
fi

# L3-L8: report-service Zanzibar env drift guard (PR #424, CNS-20260416-003).
#
# When BACKEND_DEPLOY_REMOTE_ENV_FILE (the canonical /home/halil/platform/env/backend.env
# used by deploy-backend.sh) drifts from the repo .env contract, containers come up
# with blank ERP_OPENFGA_* values — OpenFgaAuthzMeBuilder silently falls back to
# disabled mode and every /api/v1/reports response becomes an empty list (sahte yeşil).
# These assertions fail-close the scenario so post-deploy drift cannot slip through.
REPORT_CONTAINER="platform-report-service-1"
if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${REPORT_CONTAINER}$"; then
  check_nonblank_env() {
    local id="$1"
    local var="$2"
    local label="$3"
    local value
    value=$(docker exec "$REPORT_CONTAINER" printenv "$var" 2>/dev/null || echo "")
    if [ -z "$value" ]; then
      fail "${id}-${var}: ${label} empty — canonical backend.env likely drifted (compose default fell through)"
    else
      pass "${id}-${var}: ${label} set (${value:0:40}...)"
    fi
  }
  check_nonblank_env "L3" "ERP_OPENFGA_ENABLED"       "Zanzibar enabled"
  check_nonblank_env "L4" "ERP_OPENFGA_STORE_ID"      "OpenFGA store id"
  check_nonblank_env "L5" "ERP_OPENFGA_MODEL_ID"      "OpenFGA model id"
  check_nonblank_env "L6" "PERMISSION_SERVICE_BASE_URL" "Permission-service base URL"
  check_nonblank_env "L7" "AUTHZ_USER_TABLE"          "AuthenticatedUserLookup user table"
  # L8: issuer must be the ai.acik.com public URL OR match SECURITY_JWT_ISSUERS list —
  # token iss=https://ai.acik.com/realms/serban otherwise gets rejected.
  ISSUER=$(docker exec "$REPORT_CONTAINER" printenv SECURITY_JWT_ISSUER 2>/dev/null || echo "")
  ISSUERS=$(docker exec "$REPORT_CONTAINER" printenv SECURITY_JWT_ISSUERS 2>/dev/null || echo "")
  if echo "$ISSUER$ISSUERS" | grep -q "ai.acik.com"; then
    pass "L8-SECURITY_JWT_ISSUER: accepts ai.acik.com tokens (${ISSUER:-<from ISSUERS>})"
  else
    fail "L8-SECURITY_JWT_ISSUER: neither ISSUER nor ISSUERS contains 'ai.acik.com' — production tokens will 401"
  fi
else
  warn "L3-L8: container '${REPORT_CONTAINER}' çalışmıyor (skip Zanzibar env drift guard)"
fi

# ── SUMMARY ──────────────────────────────────────────────────────────
echo ""
echo "══════════════════════════════════════════════"
echo "  Checks: ${CHECKS}  |  Pass: $((CHECKS-FAILURES-WARNINGS))  |  Fail: ${FAILURES}  |  Warn: ${WARNINGS}"
if [ "${FAILURES}" -gt 0 ]; then
  echo "  STATUS: FAIL"
  echo "══════════════════════════════════════════════"
  exit 1
else
  echo "  STATUS: PASS"
  echo "══════════════════════════════════════════════"
  exit 0
fi
