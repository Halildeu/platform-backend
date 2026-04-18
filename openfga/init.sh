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

# 2. Create or reuse store (idempotent — Codex Thread 3 drift hunt)
# Pre-check: if an explicit OPENFGA_STORE_ID env var is set, verify the
# store exists by ID and reuse it (authoritative path). Otherwise look up
# by name and require exactly one match (name is not guaranteed unique in
# OpenFGA, so ambiguous results abort rather than guess).
STORE_ID="${OPENFGA_STORE_ID:-}"
if [ -n "$STORE_ID" ]; then
  echo "Verifying existing store id=$STORE_ID ..."
  if curl -sf "$OPENFGA_URL/stores/$STORE_ID" > /dev/null 2>&1; then
    echo "Store reused: $STORE_ID"
  else
    echo "ERROR: OPENFGA_STORE_ID=$STORE_ID set but not found on this OpenFGA instance"
    exit 1
  fi
fi

if [ -z "$STORE_ID" ]; then
  echo "Looking up store by name='$STORE_NAME'..."
  STORES_LIST=$(curl -sf "$OPENFGA_URL/stores" 2>/dev/null || echo '{"stores":[]}')
  STORE_ID=$(echo "$STORES_LIST" | python3 -c '
import sys, json
data = json.load(sys.stdin)
name = sys.argv[1]
matches = [s for s in data.get("stores", []) if s.get("name") == name]
if len(matches) == 1:
    print(matches[0]["id"])
elif len(matches) > 1:
    sys.stderr.write(f"ERROR: multiple stores named {name}; set OPENFGA_STORE_ID explicitly\n")
    sys.exit(2)
' "$STORE_NAME" 2>&1 || echo "")

  if [[ "$STORE_ID" == ERROR* ]]; then
    echo "$STORE_ID"
    exit 1
  fi

  if [ -n "$STORE_ID" ]; then
    echo "Store reused by name: $STORE_ID"
  else
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
  fi
fi

# 3. Render .fga model to API JSON and write authorization model.
# Idempotent: if OPENFGA_MODEL_ID is set and the model exists in this store,
# reuse it. Otherwise write a new model (OpenFGA models are append-only
# versioned; creating a new one does not invalidate existing tuples).
echo "Writing authorization model..."
if [ -n "${OPENFGA_MODEL_ID:-}" ]; then
  if curl -sf "$OPENFGA_URL/stores/$STORE_ID/authorization-models/$OPENFGA_MODEL_ID" > /dev/null 2>&1; then
    echo "Model reused: $OPENFGA_MODEL_ID"
    MODEL_RESPONSE='{"authorization_model_id":"'"$OPENFGA_MODEL_ID"'"}'
  else
    echo "WARN: OPENFGA_MODEL_ID=$OPENFGA_MODEL_ID set but not found in store; creating new model"
    OPENFGA_MODEL_ID=""
  fi
fi
if [ -z "${OPENFGA_MODEL_ID:-}" ]; then
  MODEL_PAYLOAD=$(python3 "$MODEL_RENDERER" "$MODEL_FILE")
  MODEL_RESPONSE=$(curl -sf -X POST "$OPENFGA_URL/stores/$STORE_ID/authorization-models" \
    -H "Content-Type: application/json" \
    -d "$MODEL_PAYLOAD")
fi

# 4. Get the model ID
echo "Getting model ID..."
MODEL_ID=$(echo "$MODEL_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('authorization_model_id',''))" 2>/dev/null || echo "")

if [ -z "$MODEL_ID" ]; then
  echo "WARNING: Could not retrieve model ID"
else
  echo "Model ID: $MODEL_ID"
fi

# 5. Write seed tuples
# NOTE: Tuple seed write is intentionally NOT idempotent (Codex PR #469 review).
# Tuples-seed.json is declarative; OpenFGA rejects exact duplicate (user, relation,
# object) triples with 4xx. `curl -sf` propagates that as script exit, which is the
# correct drift signal when re-running init.sh against an already-seeded store —
# the operator should either use `fga tuple write` (which short-circuits duplicates)
# or clear the store first. Store/model reuse above is the safe re-entry path;
# seed tuples remain seed-once semantics by design.
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
