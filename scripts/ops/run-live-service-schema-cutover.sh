#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.yml"
REPORT_ROOT="${ROOT_DIR}/.autopilot-tmp/live-schema-cutover"
PRE_DIR="${REPORT_ROOT}/pre"
POST_DIR="${REPORT_ROOT}/post"
ROLLBACK_DIR="${REPORT_ROOT}/rollback"
ENV_FILE="${ROOT_DIR}/.env"
ENV_BACKUP="${PRE_DIR}/.env.pre-cutover.backup"
BUSINESS_SERVICES=(api-gateway auth-service user-service permission-service variant-service core-data-service)
START_ORDER=(permission-service user-service auth-service variant-service core-data-service api-gateway)
ROLLBACK_ON_FAIL="${ROLLBACK_ON_FAIL:-1}"
CUTOVER_APPLIED=0

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

upsert_env_value() {
  local file="$1"
  local key="$2"
  local value="$3"
  if rg -q "^${key}=" "$file"; then
    perl -0pi -e "s/^${key}=.*\$/${key}=${value}/m" "$file"
  else
    printf '%s=%s\n' "$key" "$value" >> "$file"
  fi
}

rollback_live_cutover() {
  mkdir -p "${ROLLBACK_DIR}"
  local pg_container
  pg_container="$(docker compose -f "${COMPOSE_FILE}" ps -q postgres-db)"
  if [[ -z "${pg_container}" ]]; then
    echo "rollback skipped: postgres container not found" >&2
    return 1
  fi

  docker exec "${pg_container}" psql -U postgres -d users \
    -f /docker-entrypoint-initdb.d/03-offline-service-schema-cutover-rollback.sql \
    > "${ROLLBACK_DIR}/rollback.sql.out" 2>&1 || true

  if [[ -f "${ENV_BACKUP}" ]]; then
    cp "${ENV_BACKUP}" "${ENV_FILE}"
  fi

  docker compose -f "${COMPOSE_FILE}" up -d "${START_ORDER[@]}" >/dev/null 2>&1 || true

  wait_http_ok "http://localhost:8090/actuator/health" "permission-service rollback" 120 2 || true
  wait_http_ok "http://localhost:8089/actuator/health" "user-service rollback" 120 2 || true
  wait_http_ok "http://localhost:8088/actuator/health" "auth-service rollback" 120 2 || true
  wait_http_ok "http://localhost:8091/actuator/health" "variant-service rollback" 120 2 || true
  wait_http_ok "http://localhost:8092/actuator/health" "core-data-service rollback" 120 2 || true
  wait_http_ok "http://localhost:8080/actuator/health" "api-gateway rollback" 120 2 || true

  "${ROOT_DIR}/scripts/ops/capture-live-schema-cutover-lock.sh" rollback public || true
}

on_error() {
  local exit_code="$1"
  if [[ "${ROLLBACK_ON_FAIL}" == "1" && "${CUTOVER_APPLIED}" == "1" ]]; then
    rollback_live_cutover
  fi
  exit "${exit_code}"
}

trap 'rc=$?; if [[ $rc -ne 0 ]]; then on_error "$rc"; fi' EXIT

mkdir -p "${PRE_DIR}" "${POST_DIR}" "${ROLLBACK_DIR}"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "missing env file: ${ENV_FILE}" >&2
  exit 1
fi

wait_http_ok "http://localhost:8761/actuator/health" "discovery" 60 2
wait_http_ok "http://localhost:8090/actuator/health" "permission-service" 60 2
wait_http_ok "http://localhost:8089/actuator/health" "user-service" 60 2
wait_http_ok "http://localhost:8088/actuator/health" "auth-service" 60 2
wait_http_ok "http://localhost:8091/actuator/health" "variant-service" 60 2
wait_http_ok "http://localhost:8092/actuator/health" "core-data-service" 60 2
wait_http_ok "http://localhost:8080/actuator/health" "api-gateway" 60 2

"${ROOT_DIR}/scripts/ops/capture-live-schema-cutover-lock.sh" pre public

cp "${ENV_FILE}" "${ENV_BACKUP}"

PG_CONTAINER="$(docker compose -f "${COMPOSE_FILE}" ps -q postgres-db)"
if [[ -z "${PG_CONTAINER}" ]]; then
  echo "postgres container not found" >&2
  exit 1
fi

docker exec "${PG_CONTAINER}" rm -f /tmp/users-pre-cutover.dump >/dev/null 2>&1 || true
docker exec "${PG_CONTAINER}" pg_dump -U postgres -d users -Fc -f /tmp/users-pre-cutover.dump
docker cp "${PG_CONTAINER}:/tmp/users-pre-cutover.dump" "${PRE_DIR}/users-pre-cutover.dump"
docker exec "${PG_CONTAINER}" rm -f /tmp/users-pre-cutover.dump

docker compose -f "${COMPOSE_FILE}" stop "${BUSINESS_SERVICES[@]}"

docker exec "${PG_CONTAINER}" psql -U postgres -d users \
  -f /docker-entrypoint-initdb.d/02-offline-service-schema-cutover.sql \
  > "${POST_DIR}/cutover.sql.out" 2>&1
CUTOVER_APPLIED=1

export AUTH_DB_SCHEMA="auth_service"
export USER_DB_SCHEMA="user_service"
export PERMISSION_DB_SCHEMA="permission_service"
export VARIANT_DB_SCHEMA="variant_service"
export CORE_DATA_DB_SCHEMA="core_data_service"
export PERMISSION_BOOTSTRAP_DEFAULT_ADMIN_ASSIGNMENTS_USER_TABLE="user_service.users"

for service in "${START_ORDER[@]}"; do
  docker compose -f "${COMPOSE_FILE}" up -d "${service}"
done

wait_http_ok "http://localhost:8090/actuator/health" "permission-service" 120 2
wait_http_ok "http://localhost:8089/actuator/health" "user-service" 120 2
wait_http_ok "http://localhost:8088/actuator/health" "auth-service" 120 2
wait_http_ok "http://localhost:8091/actuator/health" "variant-service" 120 2
wait_http_ok "http://localhost:8092/actuator/health" "core-data-service" 120 2
wait_http_ok "http://localhost:8080/actuator/health" "api-gateway" 120 2

"${ROOT_DIR}/scripts/ops/capture-live-schema-cutover-lock.sh" post service_owned

upsert_env_value "${ENV_FILE}" "AUTH_DB_SCHEMA" "auth_service"
upsert_env_value "${ENV_FILE}" "USER_DB_SCHEMA" "user_service"
upsert_env_value "${ENV_FILE}" "PERMISSION_DB_SCHEMA" "permission_service"
upsert_env_value "${ENV_FILE}" "VARIANT_DB_SCHEMA" "variant_service"
upsert_env_value "${ENV_FILE}" "CORE_DATA_DB_SCHEMA" "core_data_service"
upsert_env_value "${ENV_FILE}" "PERMISSION_BOOTSTRAP_DEFAULT_ADMIN_ASSIGNMENTS_USER_TABLE" "user_service.users"

cp "${ENV_FILE}" "${POST_DIR}/.env.post-cutover.snapshot"

trap - EXIT
