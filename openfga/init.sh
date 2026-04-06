#!/usr/bin/env bash
# OpenFGA initialization script
# Usage: ./init.sh [OPENFGA_URL]
set -euo pipefail

OPENFGA_URL="${1:-http://localhost:4000}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODEL_FILE="${MODEL_FILE:-$SCRIPT_DIR/model.fga}"
MODEL_RENDERER="${MODEL_RENDERER:-$SCRIPT_DIR/render_model_json.py}"
STORE_NAME="${OPENFGA_STORE_NAME:-erp-stage}"

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
echo "Creating store '$STORE_NAME'..."
STORE_RESPONSE=$(curl -sf -X POST "$OPENFGA_URL/stores" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"$STORE_NAME\"}")

STORE_ID=$(echo "$STORE_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "")

if [ -z "$STORE_ID" ]; then
  echo "ERROR: Failed to create store"
  echo "$STORE_RESPONSE"
  exit 1
fi
echo "Store created: $STORE_ID"

# 3. Render .fga model to API JSON and write authorization model
echo "Writing authorization model..."
MODEL_PAYLOAD=$(python3 "$MODEL_RENDERER" "$MODEL_FILE")
MODEL_RESPONSE=$(curl -sf -X POST "$OPENFGA_URL/stores/$STORE_ID/authorization-models" \
  -H "Content-Type: application/json" \
  -d "$MODEL_PAYLOAD")

# 4. Get the model ID
echo "Getting model ID..."
MODEL_ID=$(echo "$MODEL_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('authorization_model_id',''))" 2>/dev/null || echo "")

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
