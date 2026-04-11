#!/usr/bin/env bash
set -euo pipefail
# TB-10: @Filter CI gate — ensures all company-scoped entities have Hibernate @Filter.
# CNS-20260411-003: prevent new company-scoped entities without @Filter.

BACKEND_DIR="${1:-$(cd "$(dirname "$0")/../.." && pwd)}"
ERRORS=0

echo "[filter-gate] Scanning company-scoped entities for @Filter annotation..."

# Find all entity classes with company_id or companyId field
for entity in $(grep -rl "company_id\|companyId" "$BACKEND_DIR"/*/src/main/java/ --include="*.java" 2>/dev/null | grep -v target | grep -v test); do
    # Skip non-entity classes
    if ! grep -q "@Entity\|@Table" "$entity" 2>/dev/null; then
        continue
    fi

    basename=$(basename "$entity")
    has_filter=$(grep -c "@FilterDef\|@Filter" "$entity" 2>/dev/null || true)

    if [ "${has_filter:-0}" -eq 0 ]; then
        echo "  FAIL: $entity — company-scoped @Entity without @Filter"
        ERRORS=$((ERRORS + 1))
    else
        echo "  OK: $basename"
    fi
done

if [ "$ERRORS" -gt 0 ]; then
    echo ""
    echo "[filter-gate] FAIL: $ERRORS company-scoped entities missing @Filter"
    exit 1
fi

echo "[filter-gate] PASS: all company-scoped entities have @Filter"
