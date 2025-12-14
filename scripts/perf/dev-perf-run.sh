#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

echo "[dev-perf] Bringing up core services (docker-compose.yml)"
docker compose -f "$ROOT_DIR/docker-compose.yml" up -d discovery-server postgres-db auth-service user-service variant-service permission-service api-gateway

echo "[dev-perf] Bringing up observability stack (Prometheus + Grafana)"
docker compose -f "$ROOT_DIR/scripts/perf/docker-compose.perf.yml" up -d

echo "[dev-perf] Done. Access:"
echo " - API Gateway:     http://localhost:8080"
echo " - User Service:    http://localhost:8089"
echo " - Prometheus:      http://localhost:9090"
echo " - Grafana:         http://localhost:3001 (admin/admin)"

