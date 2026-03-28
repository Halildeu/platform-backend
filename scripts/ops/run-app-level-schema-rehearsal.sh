#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/devops/rehearsal/docker-compose.app-level-schema.yml"
PROJECT_NAME="${APP_LEVEL_REHEARSAL_PROJECT:-serban-schema-rehearsal}"
REPORT_DIR="${ROOT_DIR}/.autopilot-tmp/app-level-schema-rehearsal"
KEEP_STACK="${KEEP_APP_LEVEL_REHEARSAL_STACK:-0}"
mkdir -p "${REPORT_DIR}"

cleanup() {
  if [[ "${KEEP_STACK}" != "1" ]]; then
    docker compose -p "${PROJECT_NAME}" -f "${COMPOSE_FILE}" down -v --remove-orphans >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

docker compose -p "${PROJECT_NAME}" -f "${COMPOSE_FILE}" down -v --remove-orphans >/dev/null 2>&1 || true
docker compose -p "${PROJECT_NAME}" -f "${COMPOSE_FILE}" up -d --build

wait_http_ok() {
  local url="$1"
  local label="$2"
  local attempts="${3:-90}"
  local sleep_seconds="${4:-2}"
  for ((i=1; i<=attempts; i++)); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep "$sleep_seconds"
  done
  echo "timeout waiting for ${label}: ${url}" >&2
  return 1
}

wait_http_ok "http://localhost:18761/actuator/health" "discovery"
wait_http_ok "http://localhost:18090/actuator/health" "permission-service"
wait_http_ok "http://localhost:18089/actuator/health" "user-service"
wait_http_ok "http://localhost:18088/actuator/health" "auth-service"
wait_http_ok "http://localhost:18091/actuator/health" "variant-service"
wait_http_ok "http://localhost:18092/actuator/health" "core-data-service"
wait_http_ok "http://localhost:18080/actuator/health" "api-gateway"

SERVICES=(discovery-server postgres-db permission-service user-service auth-service variant-service core-data-service api-gateway)
for service in "${SERVICES[@]}"; do
  docker compose -p "${PROJECT_NAME}" -f "${COMPOSE_FILE}" logs --no-color "$service" > "${REPORT_DIR}/${service}.log"
done

docker compose -p "${PROJECT_NAME}" -f "${COMPOSE_FILE}" ps --format json > "${REPORT_DIR}/compose-ps.json"

curl -fsS http://localhost:18761/eureka/apps > "${REPORT_DIR}/eureka-apps.xml"
for svc in discovery permission-service user-service auth-service variant-service core-data-service api-gateway; do
  case "$svc" in
    discovery) port=18761 ;;
    permission-service) port=18090 ;;
    user-service) port=18089 ;;
    auth-service) port=18088 ;;
    variant-service) port=18091 ;;
    core-data-service) port=18092 ;;
    api-gateway) port=18080 ;;
  esac
  curl -fsS "http://localhost:${port}/actuator/health" > "${REPORT_DIR}/${svc}-health.json"
done

curl -sS -D "${REPORT_DIR}/gateway-companies-headers.txt" http://localhost:18080/api/v1/companies -o "${REPORT_DIR}/gateway-companies-body.txt"

PG_CONTAINER=$(docker compose -p "${PROJECT_NAME}" -f "${COMPOSE_FILE}" ps -q postgres-db)
if [[ -z "${PG_CONTAINER}" ]]; then
  echo "postgres rehearsal container not found" >&2
  exit 1
fi

docker exec "$PG_CONTAINER" psql -U postgres -d schema_rehearsal -c "SELECT schemaname, tablename FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema') ORDER BY schemaname, tablename;" > "${REPORT_DIR}/schema-inventory.txt"
docker exec "$PG_CONTAINER" psql -U postgres -d schema_rehearsal -c "SELECT u.email, r.name, ura.active FROM permission_service.user_role_assignments ura JOIN user_service.users u ON u.id = ura.user_id JOIN permission_service.roles r ON r.id = ura.role_id WHERE lower(u.email) IN ('admin@example.com','admin1@example.com') ORDER BY u.email, r.name;" > "${REPORT_DIR}/admin-role-assignments.txt"
docker exec "$PG_CONTAINER" psql -U postgres -d schema_rehearsal -c "SELECT 'auth' AS service, count(*) AS rows FROM auth_service.auth_flyway_history UNION ALL SELECT 'user', count(*) FROM user_service.user_flyway_history UNION ALL SELECT 'permission', count(*) FROM permission_service.permission_flyway_history UNION ALL SELECT 'variant', count(*) FROM variant_service.variant_flyway_history UNION ALL SELECT 'core-data', count(*) FROM core_data_service.core_data_flyway_history;" > "${REPORT_DIR}/flyway-history-counts.txt"

grep -E 'AUTH-SERVICE|USER-SERVICE|PERMISSION-SERVICE|VARIANT-SERVICE|CORE-DATA-SERVICE' "${REPORT_DIR}/eureka-apps.xml" > "${REPORT_DIR}/eureka-services.txt"
cat "${REPORT_DIR}/schema-inventory.txt"
echo
cat "${REPORT_DIR}/admin-role-assignments.txt"
echo
cat "${REPORT_DIR}/flyway-history-counts.txt"
echo
cat "${REPORT_DIR}/gateway-companies-headers.txt"
