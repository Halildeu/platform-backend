#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SQL_FILE="${ROOT_DIR}/devops/postgres/03-offline-service-schema-cutover-rollback.sql"
CONTAINER_NAME="${POSTGRES_CONTAINER_NAME:-serban-postgres-db-1}"
POSTGRES_USER="${POSTGRES_USER:-postgres}"
POSTGRES_DB="${POSTGRES_DB:-users}"

if [[ ! -f "${SQL_FILE}" ]]; then
  echo "missing sql file: ${SQL_FILE}" >&2
  exit 1
fi

docker exec -i "${CONTAINER_NAME}" psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" < "${SQL_FILE}"
