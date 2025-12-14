#!/usr/bin/env bash
set -euo pipefail

TOKEN="${1:-}"
if [[ -z "$TOKEN" ]]; then
  echo "Kullanım: scripts/jwt-decode.sh <JWT>" 1>&2
  exit 1
fi

payload=$(printf '%s' "$TOKEN" | cut -d. -f2)
pad=$(( (4 - ${#payload} % 4) % 4 ))
printf -v padstr '%*s' "$pad" ''
padstr=${padstr// /=}
decoded=$(printf '%s' "$payload$padstr" | tr '_-' '/+' | base64 -d 2>/dev/null || true)

if command -v jq >/dev/null 2>&1; then
  printf '%s\n' "$decoded" | jq .
else
  printf '%s\n' "$decoded"
fi

