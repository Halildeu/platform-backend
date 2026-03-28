#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONTAINER_NAME="${POSTGRES_CONTAINER_NAME:-serban-postgres-db-1}"
POSTGRES_USER="${POSTGRES_USER:-postgres}"
SOURCE_DB="${SOURCE_POSTGRES_DB:-users}"
REHEARSAL_DB="${REHEARSAL_POSTGRES_DB:-schema_cutover_rehearsal}"
CUTOVER_SQL="${ROOT_DIR}/devops/postgres/02-offline-service-schema-cutover.sql"
ROLLBACK_SQL="${ROOT_DIR}/devops/postgres/03-offline-service-schema-cutover-rollback.sql"
REPORT_DIR="${ROOT_DIR}/.autopilot-tmp/cutover-rehearsal"
mkdir -p "${REPORT_DIR}"
KEEP_REHEARSAL_DB="${KEEP_REHEARSAL_DB:-0}"

docker exec "${CONTAINER_NAME}" dropdb --if-exists -U "${POSTGRES_USER}" "${REHEARSAL_DB}" >/dev/null 2>&1 || true
docker exec "${CONTAINER_NAME}" createdb -U "${POSTGRES_USER}" "${REHEARSAL_DB}"

docker exec "${CONTAINER_NAME}" pg_dump -U "${POSTGRES_USER}" --schema-only --no-owner --no-privileges "${SOURCE_DB}" \
  | docker exec -i "${CONTAINER_NAME}" psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER}" -d "${REHEARSAL_DB}" \
  > "${REPORT_DIR}/schema-load.log"

docker exec -i "${CONTAINER_NAME}" psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER}" -d "${REHEARSAL_DB}" < "${CUTOVER_SQL}" \
  > "${REPORT_DIR}/cutover-apply.log"

docker exec "${CONTAINER_NAME}" psql -U "${POSTGRES_USER}" -d "${REHEARSAL_DB}" -c "SELECT schemaname, tablename FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema') ORDER BY schemaname, tablename;" \
  > "${REPORT_DIR}/post-cutover-inventory.txt"

docker exec -i "${CONTAINER_NAME}" psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER}" -d "${REHEARSAL_DB}" < "${ROLLBACK_SQL}" \
  > "${REPORT_DIR}/rollback-apply.log"

docker exec "${CONTAINER_NAME}" psql -U "${POSTGRES_USER}" -d "${REHEARSAL_DB}" -c "SELECT schemaname, tablename FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema') ORDER BY schemaname, tablename;" \
  > "${REPORT_DIR}/post-rollback-inventory.txt"

cat "${REPORT_DIR}/post-cutover-inventory.txt"
echo
echo "---"
echo
cat "${REPORT_DIR}/post-rollback-inventory.txt"

if [[ "${KEEP_REHEARSAL_DB}" != "1" ]]; then
  docker exec "${CONTAINER_NAME}" dropdb --if-exists -U "${POSTGRES_USER}" "${REHEARSAL_DB}" >/dev/null 2>&1 || true
fi
