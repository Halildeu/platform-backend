#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCHEMA_DIR="$ROOT/manifest"

echo "== Manifest schema validation =="
echo "Schema: $SCHEMA_DIR/manifest.schema.json"
echo "Samples: $SCHEMA_DIR/examples/*manifest.json"

if ! command -v npx >/dev/null 2>&1; then
  echo "npx bulunamadı. Node.js/npm kurulu olmalı."
  exit 1
fi

npx ajv-cli validate --strict=false -s "$SCHEMA_DIR/manifest.schema.json" -d "$SCHEMA_DIR/examples/*manifest.json"

echo "== PageLayout schema validation =="
echo "Schema: $SCHEMA_DIR/page-layout.schema.json"

npx ajv-cli validate --strict=false -s "$SCHEMA_DIR/page-layout.schema.json" -d "$SCHEMA_DIR/examples/page-*.layout.json"

echo "Tamam."
