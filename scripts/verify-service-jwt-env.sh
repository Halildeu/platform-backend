#!/usr/bin/env bash
set -euo pipefail

MISSING=()

for var in SERVICE_JWT_PRIVATE_KEY SERVICE_JWT_PUBLIC_KEY SERVICE_JWT_KEY_ID; do
  if [[ -z ${!var-} ]]; then
    MISSING+=("$var")
  fi
done

if (( ${#MISSING[@]} > 0 )); then
  echo "Eksik servis JWT değişkenleri: ${MISSING[*]}" >&2
  exit 1
fi

echo "Servis JWT ortam değişkenleri mevcut."
