#!/usr/bin/env bash
set -euo pipefail

# Seed 1200 users into Postgres using the Flyway seed file.
# Works with Docker Compose (preferred) or local psql fallback.

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SEED_FILE="user-service/src/main/resources/db/migration/V4__seed_1200_users.sql"
DB_NAME="${POSTGRES_DB:-users}"
DB_USER="${POSTGRES_USER:-postgres}"

if [[ ! -f "$ROOT_DIR/$SEED_FILE" ]]; then
  echo "Seed dosyası bulunamadı: $SEED_FILE" >&2
  exit 1
fi

echo "[seed-users] Tespit: Docker Compose konteyneri var mı?"
if docker compose ps >/dev/null 2>&1; then
  # Service olarak exec; STDIN'den SQL beslemek için -T gerekli
  if docker compose ps postgres-db | grep -q postgres-db; then
    echo "[seed-users] docker compose exec ile çalıştırılıyor (service=postgres-db, db=$DB_NAME, user=$DB_USER)"
    cat "$ROOT_DIR/$SEED_FILE" | docker compose exec -T postgres-db \
      env PGPASSWORD="${POSTGRES_PASSWORD:-postgres}" \
      psql -U "$DB_USER" -d "$DB_NAME" -f -
    echo "[seed-users] Seed tamamlandı."
    exit 0
  fi
fi

echo "[seed-users] Docker Compose bulunamadı ya da postgres-db servisi yok. Lokal psql ile deneniyor..."
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
export PGPASSWORD="${POSTGRES_PASSWORD:-postgres}"

psql --version >/dev/null 2>&1 || { echo "psql bulunamadı. Postgres istemcisini kurun ya da Docker Compose kullanın." >&2; exit 2; }

psql -h "$PGHOST" -p "$PGPORT" -U "$DB_USER" -d "$DB_NAME" -f "$ROOT_DIR/$SEED_FILE"
echo "[seed-users] Seed tamamlandı (lokal psql)."

