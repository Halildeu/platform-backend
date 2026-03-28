#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.yml"
PHASE="${1:-}"
QUERY_MODE="${2:-public}"

if [[ -z "${PHASE}" ]]; then
  echo "usage: $0 <phase> [public|service_owned]" >&2
  exit 1
fi

REPORT_DIR="${ROOT_DIR}/.autopilot-tmp/live-schema-cutover/${PHASE}"
mkdir -p "${REPORT_DIR}"

docker compose -f "${COMPOSE_FILE}" ps --format json > "${REPORT_DIR}/compose-ps.json"
docker compose -f "${COMPOSE_FILE}" config | \
  rg "AUTH_DB_SCHEMA|USER_DB_SCHEMA|PERMISSION_DB_SCHEMA|VARIANT_DB_SCHEMA|CORE_DATA_DB_SCHEMA|PERMISSION_BOOTSTRAP_DEFAULT_ADMIN_ASSIGNMENTS_USER_TABLE" \
  > "${REPORT_DIR}/compose-schema-config.txt" || true

curl -fsS http://localhost:8761/eureka/apps > "${REPORT_DIR}/eureka-apps.xml"

for svc in discovery permission-service user-service auth-service variant-service core-data-service api-gateway; do
  case "$svc" in
    discovery) port=8761 ;;
    permission-service) port=8090 ;;
    user-service) port=8089 ;;
    auth-service) port=8088 ;;
    variant-service) port=8091 ;;
    core-data-service) port=8092 ;;
    api-gateway) port=8080 ;;
  esac
  curl -fsS "http://localhost:${port}/actuator/health" > "${REPORT_DIR}/${svc}-health.json"
done

curl -sS -D "${REPORT_DIR}/gateway-companies-headers.txt" \
  http://localhost:8080/api/v1/companies \
  -o "${REPORT_DIR}/gateway-companies-body.txt"

PG_CONTAINER="$(docker compose -f "${COMPOSE_FILE}" ps -q postgres-db)"
if [[ -z "${PG_CONTAINER}" ]]; then
  echo "postgres container not found" >&2
  exit 1
fi

docker exec "${PG_CONTAINER}" psql -U postgres -d users \
  -c "SELECT schemaname, tablename FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema') ORDER BY schemaname, tablename;" \
  > "${REPORT_DIR}/schema-inventory.txt"

docker exec "${PG_CONTAINER}" psql -U postgres -d users \
  -c "SELECT schemaname, sequencename FROM pg_sequences WHERE schemaname NOT IN ('pg_catalog','information_schema') ORDER BY schemaname, sequencename;" \
  > "${REPORT_DIR}/sequence-inventory.txt"

if [[ "${QUERY_MODE}" == "service_owned" ]]; then
  docker exec "${PG_CONTAINER}" psql -U postgres -d users \
    -c "SELECT u.email, r.name, ura.active FROM permission_service.user_role_assignments ura JOIN user_service.users u ON u.id = ura.user_id JOIN permission_service.roles r ON r.id = ura.role_id WHERE lower(u.email) IN ('admin@example.com','admin1@example.com') ORDER BY u.email, r.name;" \
    > "${REPORT_DIR}/admin-role-assignments.txt"
  docker exec "${PG_CONTAINER}" psql -U postgres -d users \
    -c "SELECT 'auth' AS service, count(*) AS rows FROM auth_service.auth_flyway_history UNION ALL SELECT 'user', count(*) FROM user_service.user_flyway_history UNION ALL SELECT 'permission', count(*) FROM permission_service.permission_flyway_history UNION ALL SELECT 'variant', count(*) FROM variant_service.variant_flyway_history UNION ALL SELECT 'core-data', count(*) FROM core_data_service.core_data_flyway_history;" \
    > "${REPORT_DIR}/flyway-history-counts.txt"
else
  docker exec "${PG_CONTAINER}" psql -U postgres -d users \
    -c "SELECT u.email, r.name, ura.active FROM public.user_role_assignments ura JOIN public.users u ON u.id = ura.user_id JOIN public.roles r ON r.id = ura.role_id WHERE lower(u.email) IN ('admin@example.com','admin1@example.com') ORDER BY u.email, r.name;" \
    > "${REPORT_DIR}/admin-role-assignments.txt"
  docker exec "${PG_CONTAINER}" psql -U postgres -d users \
    -c "SELECT 'auth' AS service, count(*) AS rows FROM public.auth_flyway_history UNION ALL SELECT 'user', count(*) FROM public.user_flyway_history UNION ALL SELECT 'permission', count(*) FROM public.permission_flyway_history UNION ALL SELECT 'variant', count(*) FROM public.variant_flyway_history UNION ALL SELECT 'core-data', count(*) FROM public.core_data_flyway_history;" \
    > "${REPORT_DIR}/flyway-history-counts.txt"
fi

docker compose -f "${COMPOSE_FILE}" logs --no-color --tail=200 \
  discovery-server permission-service user-service auth-service variant-service core-data-service api-gateway \
  > "${REPORT_DIR}/services-tail.log"
