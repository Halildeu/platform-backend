#!/bin/bash
# dev-start.sh — Tek komutla tum dev stack'i baslatir
# Kullanim: cd backend && ./dev-start.sh
#
# Baslatma sirasi:
#   1. Altyapi  : postgres, keycloak, vault + unseal, eureka
#   2. Core     : permission-service, core-data-service
#   3. Uygulama : user-service, auth-service, variant-service
#   4. Gateway  : api-gateway
#   5. Ekler    : report-service, grafana, prometheus
#   6. Frontend : mfe-shell (webpack dev server)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

log()  { echo -e "${GREEN}[✓]${NC} $1"; }
wait_msg() { echo -ne "${CYAN}[⏳]${NC} $1\r"; }
warn() { echo -e "${YELLOW}[!]${NC} $1"; }
err()  { echo -e "${RED}[✗]${NC} $1"; }
header() { echo -e "\n${BOLD}━━━ $1 ━━━${NC}"; }

wait_healthy() {
  local svc="$1" max="${2:-90}" elapsed=0
  while [ $elapsed -lt $max ]; do
    status=$(docker compose ps "$svc" --format "{{.Status}}" 2>/dev/null || echo "")
    if echo "$status" | grep -q "healthy"; then
      log "$svc healthy"
      return 0
    fi
    if echo "$status" | grep -q "exited\|Exit"; then
      err "$svc crashed! Loglar:"
      docker compose logs --tail=15 "$svc" 2>/dev/null
      return 1
    fi
    wait_msg "$svc bekleniyor... (${elapsed}s/${max}s)"
    sleep 3
    elapsed=$((elapsed + 3))
  done
  warn "$svc ${max}s icinde hazir olmadi"
  return 1
}

# ---- Onceki calisanlari temizle ----
header "1/6 ALTYAPI"
log "Altyapi servisleri baslatiliyor (postgres, keycloak, vault, eureka)..."
docker compose up -d postgres-db keycloak discovery-server vault vault-unseal 2>&1 | grep -v "^$"

wait_healthy postgres-db 60
wait_healthy discovery-server 60

# Vault unseal bekleme — key varsa otomatik acilir
log "Vault unseal bekleniyor..."
wait_healthy vault 120 || warn "Vault unseal olamadi — servislere etkisi sinirli"

# ---- Core servisler ----
header "2/6 CORE SERViSLER"
docker compose up -d permission-service core-data-service 2>&1 | grep -v "^$"
wait_healthy permission-service 90
wait_healthy core-data-service 90

# ---- Uygulama servisleri ----
header "3/6 UYGULAMA SERViSLERi"
docker compose up -d user-service auth-service variant-service 2>&1 | grep -v "^$"
wait_healthy user-service 90
wait_healthy auth-service 90
wait_healthy variant-service 90

# ---- API Gateway ----
header "4/6 API GATEWAY"
docker compose up -d api-gateway 2>&1 | grep -v "^$"
wait_healthy api-gateway 90

# ---- Opsiyonel servisler ----
header "5/6 EK SERViSLER"
docker compose up -d report-service observability-prometheus observability-grafana 2>&1 | grep -v "^$"
log "Report-service baslatildi (SQL Server erisimi opsiyonel)"

# ---- Frontend ----
header "6/6 FRONTEND"
FRONTEND_DIR="$SCRIPT_DIR/../web/apps/mfe-shell"
if [ -d "$FRONTEND_DIR" ]; then
  # Zaten calisiyor mu kontrol et
  if curl -s -o /dev/null -w "%{http_code}" http://localhost:3000 2>/dev/null | grep -q "200"; then
    log "Frontend zaten calisiyor (localhost:3000)"
  else
    log "Frontend baslatiliyor..."
    cd "$FRONTEND_DIR"
    SKIP_BACKEND_GUARD=1 npx webpack serve --config webpack.dev.js --port 3000 --no-watch-options-stdin &>/tmp/frontend-dev.log &
    FRONTEND_PID=$!
    cd "$SCRIPT_DIR"

    # Frontend'in baslamasini bekle
    for i in $(seq 1 30); do
      if curl -s -o /dev/null -w "%{http_code}" http://localhost:3000 2>/dev/null | grep -q "200"; then
        log "Frontend hazir (PID: $FRONTEND_PID)"
        break
      fi
      wait_msg "Frontend derleniyor... (${i}s/30s)"
      sleep 1
    done
  fi
else
  warn "Frontend dizini bulunamadi: $FRONTEND_DIR"
fi

# ---- DURUM RAPORU ----
header "DEV STACK DURUM RAPORU"
echo ""

docker compose ps --format "table {{.Name}}\t{{.Status}}" 2>&1

echo ""
HEALTHY=$(docker compose ps --format "{{.Status}}" 2>/dev/null | grep -c "healthy" || true)
TOTAL=$(docker compose ps --format "{{.Name}}" 2>/dev/null | wc -l | tr -d ' ')

echo -e "${BOLD}Endpoint'ler:${NC}"
for ep in "Frontend|http://localhost:3000|3000" "API Gateway|http://localhost:8080|8080" "Keycloak|http://localhost:8081|8081" "Eureka|http://localhost:8761|8761" "Vault|http://localhost:8200|8200" "Grafana|http://localhost:3010|3010"; do
  IFS='|' read -r name url port <<< "$ep"
  code=$(curl -s -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || echo "000")
  if [ "$code" = "000" ]; then
    echo -e "  ${RED}✗${NC} $name  localhost:$port  (erisim yok)"
  elif [ "$code" = "200" ] || [ "$code" = "302" ]; then
    echo -e "  ${GREEN}✓${NC} $name  localhost:$port  ($code)"
  else
    echo -e "  ${YELLOW}!${NC} $name  localhost:$port  ($code)"
  fi
done

echo ""
log "${BOLD}Saglikli: ${HEALTHY}/${TOTAL} servis${NC}"
echo ""
