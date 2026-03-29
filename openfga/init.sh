#!/usr/bin/env bash
# OpenFGA initialization script
# Usage: ./init.sh [OPENFGA_URL]
set -euo pipefail

OPENFGA_URL="${1:-http://localhost:4000}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== OpenFGA Init ==="
echo "URL: $OPENFGA_URL"

# 1. Wait for OpenFGA to be ready
echo "Waiting for OpenFGA..."
for i in $(seq 1 30); do
  if curl -sf "$OPENFGA_URL/healthz" > /dev/null 2>&1; then
    echo "OpenFGA is ready."
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "ERROR: OpenFGA not ready after 30s"
    exit 1
  fi
  sleep 1
done

# 2. Create store
echo "Creating store 'erp-dev'..."
STORE_RESPONSE=$(curl -sf -X POST "$OPENFGA_URL/stores" \
  -H "Content-Type: application/json" \
  -d '{"name":"erp-dev"}')

STORE_ID=$(echo "$STORE_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "")

if [ -z "$STORE_ID" ]; then
  echo "ERROR: Failed to create store"
  echo "$STORE_RESPONSE"
  exit 1
fi
echo "Store created: $STORE_ID"

# 3. Convert .fga model to JSON and write authorization model
echo "Writing authorization model..."

# Read the .fga file and convert to JSON format
MODEL_JSON=$(python3 -c "
import json, re, sys

with open('$SCRIPT_DIR/model.fga') as f:
    content = f.read()

type_defs = []
current_type = None
current_relations = {}

for line in content.split('\n'):
    line = line.rstrip()
    if line.startswith('type '):
        if current_type:
            relations = []
            for rname, rdef in current_relations.items():
                relation = {'name': rname}
                # Parse the relation definition
                parts = rdef.strip()
                relation['typeDefinition'] = parts
                relations.append(relation)
            type_defs.append({'type': current_type, 'relations': current_relations})
        current_type = line.split()[1]
        current_relations = {}
    elif line.strip().startswith('define '):
        parts = line.strip()[7:]  # remove 'define '
        name, definition = parts.split(': ', 1)
        current_relations[name] = definition

if current_type:
    type_defs.append({'type': current_type, 'relations': current_relations})

print(json.dumps(type_defs, indent=2))
")

# Use the OpenFGA CLI-compatible JSON format
# We'll write the model using the DSL directly via the WriteAuthorizationModel API
# OpenFGA accepts the model in its own JSON schema format
# For simplicity, use the fga CLI if available, otherwise use raw API

if command -v fga &> /dev/null; then
  echo "Using fga CLI to write model..."
  FGA_API_URL="$OPENFGA_URL" FGA_STORE_ID="$STORE_ID" \
    fga model write --file "$SCRIPT_DIR/model.fga"
else
  echo "fga CLI not found. Install with: brew install openfga/tap/fga"
  echo "Or download from: https://github.com/openfga/cli/releases"
  echo ""
  echo "Manual steps:"
  echo "  export FGA_API_URL=$OPENFGA_URL"
  echo "  export FGA_STORE_ID=$STORE_ID"
  echo "  fga model write --file $SCRIPT_DIR/model.fga"
  echo ""
  echo "Alternatively, use the Playground UI at http://localhost:4002"
  echo "  1. Select store: $STORE_ID"
  echo "  2. Paste model.fga content"
  echo "  3. Save"
  echo ""
  echo "Store ID for .env: $STORE_ID"
  exit 0
fi

# 4. Get the model ID
echo "Getting model ID..."
MODEL_ID=$(FGA_API_URL="$OPENFGA_URL" FGA_STORE_ID="$STORE_ID" \
  fga model get --field id 2>/dev/null || echo "")

if [ -z "$MODEL_ID" ]; then
  echo "WARNING: Could not retrieve model ID"
else
  echo "Model ID: $MODEL_ID"
fi

# 5. Write seed tuples
echo "Writing seed tuples..."
FGA_API_URL="$OPENFGA_URL" FGA_STORE_ID="$STORE_ID" \
  fga tuple write --file "$SCRIPT_DIR/tuples-seed.json" 2>/dev/null || \
  curl -sf -X POST "$OPENFGA_URL/stores/$STORE_ID/write" \
    -H "Content-Type: application/json" \
    -d @"$SCRIPT_DIR/tuples-seed.json"

echo ""
echo "=== OpenFGA Init Complete ==="
echo ""
echo "Store ID:  $STORE_ID"
echo "Model ID:  ${MODEL_ID:-see Playground}"
echo ""
echo "Add to your .env:"
echo "  OPENFGA_STORE_ID=$STORE_ID"
echo "  OPENFGA_MODEL_ID=${MODEL_ID:-<get from playground>}"
echo ""
echo "Test check:"
echo "  curl -X POST $OPENFGA_URL/stores/$STORE_ID/check \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"tuple_key\":{\"user\":\"user:1\",\"relation\":\"viewer\",\"object\":\"company:1\"}}'"
echo ""
echo "Playground: http://localhost:4002"
