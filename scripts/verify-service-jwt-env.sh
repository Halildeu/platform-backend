#!/usr/bin/env bash
set -euo pipefail

MISSING=()

# OI-02 post-mortem (2026-04-18): canonical vars are AUTH_SERVICE_JWT_* per
# auth-service application.properties + render-backend-env.sh + prod compose.
# SERVICE_JWT_KEY_ID is shared across services (not auth-specific).
for var in AUTH_SERVICE_JWT_PRIVATE_KEY AUTH_SERVICE_JWT_PUBLIC_KEY SERVICE_JWT_KEY_ID; do
  if [[ -z ${!var-} ]]; then
    MISSING+=("$var")
  fi
done

if (( ${#MISSING[@]} > 0 )); then
  echo "Eksik servis JWT değişkenleri: ${MISSING[*]}" >&2
  exit 1
fi

echo "Servis JWT ortam değişkenleri mevcut."
