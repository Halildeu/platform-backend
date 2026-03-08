#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONTAINER_NAME="${POSTGRES_CONTAINER_NAME:-serban-postgres-db-1}"
POSTGRES_USER="${POSTGRES_USER:-postgres}"
REPORT_DIR="${ROOT_DIR}/.autopilot-tmp/baseline-validation"
mkdir -p "${REPORT_DIR}"
KEEP_VALIDATION_DBS="${KEEP_VALIDATION_DBS:-0}"

validate_chain() {
  local service="$1"
  local dir="$2"
  local db_name="$3"

  docker exec "${CONTAINER_NAME}" dropdb --if-exists -U "${POSTGRES_USER}" "${db_name}" >/dev/null 2>&1 || true
  docker exec "${CONTAINER_NAME}" createdb -U "${POSTGRES_USER}" "${db_name}"

  {
    echo "service=${service}"
    echo "database=${db_name}"
    for file in $(find "${dir}" -maxdepth 1 -name 'V*.sql' | sort -V); do
      echo ">>> applying $(basename "${file}")"
      docker exec -i "${CONTAINER_NAME}" psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER}" -d "${db_name}" < "${file}"
    done
    echo ">>> object inventory"
    docker exec "${CONTAINER_NAME}" psql -U "${POSTGRES_USER}" -d "${db_name}" -c "SELECT schemaname, tablename FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema') ORDER BY schemaname, tablename;"
  } | tee "${REPORT_DIR}/${service}.log"

  if [[ "${KEEP_VALIDATION_DBS}" != "1" ]]; then
    docker exec "${CONTAINER_NAME}" dropdb --if-exists -U "${POSTGRES_USER}" "${db_name}" >/dev/null 2>&1 || true
  fi
}

validate_chain "auth-service" "${ROOT_DIR}/auth-service/src/main/resources/db/migration_schema_owned" "baseline_auth_service"
validate_chain "user-service" "${ROOT_DIR}/user-service/src/main/resources/db/migration_schema_owned" "baseline_user_service"
validate_chain "permission-service" "${ROOT_DIR}/permission-service/src/main/resources/db/migration_schema_owned" "baseline_permission_service"
validate_chain "core-data-service" "${ROOT_DIR}/core-data-service/src/main/resources/db/migration_schema_owned" "baseline_core_data_service"
validate_chain "variant-service" "${ROOT_DIR}/variant-service/src/main/resources/db/migration_schema_owned" "baseline_variant_service"
