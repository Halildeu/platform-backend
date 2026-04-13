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
else
  warn "A0: Deploy script not found at $DEPLOY_SCRIPT"
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
