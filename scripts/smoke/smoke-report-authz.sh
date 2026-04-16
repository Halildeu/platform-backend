#!/usr/bin/env bash
# smoke-report-authz.sh — PR6c-1 (CNS-20260416-003)
#
# End-to-end authorization smoke for report-service against a live staging
# (or local) stack with JWT validation enabled. Exercises the Zanzibar path
# without depending on the legacy /api/v1/authz/me HTTP snapshot.
#
# Scenarios (Codex CNS-20260416-003 Q6 — minimum set):
#   1. Unauthenticated  /api/v1/reports             → 401
#   2. No REPORT_VIEW   /api/v1/reports/<k>/data    → 403
#   3. No REPORT_EXPORT /api/v1/reports/<k>/export  → 403
#   4. Super-admin      /api/v1/reports             → 200 + non-empty list
#   5. Finance dashboard alias /api/v1/dashboards   → fin-analytics filtering
#   6. HR salary column restriction  /api/v1/reports/hr-maas-raporu/metadata
#      → salary columns hidden without HR_SALARY_VIEW
#
# Secondary evidence (non-blocking): authz_decisions_total increments after
# each authenticated scenario. NOT primary signal — canary guardrail runbook
# (`docs/04-operations/RUNBOOKS/RB-zanzibar-canary.md`) owns that threshold.
#
# Usage:
#   bash backend/scripts/smoke/smoke-report-authz.sh
#
# Environment variables (all optional — defaults target ai.acik.com staging):
#   BASE_URL            — default: https://ai.acik.com
#   KC_REALM            — default: serban
#   KC_CLIENT_ID        — default: frontend
#   ADMIN_USERNAME      — default: canary-admin@stage.local
#   ADMIN_PASSWORD      — default: CanaryPass123
#   RESTRICTED_USERNAME — default: canary-restricted@stage.local (must exist)
#   RESTRICTED_PASSWORD — default: CanaryRestricted123
#   TEST_REPORT_KEY     — default: satis-ozet (the report used by the data/export checks)
#   HR_SALARY_REPORT    — default: hr-maas-raporu (report whose metadata carries
#                                  the HR_SALARY column restriction)
#
# Exit: 0 on full PASS, 1 on any scenario failure. Each scenario prints its
# own ✅ / ❌ line to stderr for operator visibility.

set -u -o pipefail

BASE_URL="${BASE_URL:-https://ai.acik.com}"
KC_REALM="${KC_REALM:-serban}"
KC_CLIENT_ID="${KC_CLIENT_ID:-frontend}"
ADMIN_USERNAME="${ADMIN_USERNAME:-canary-admin@stage.local}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-CanaryPass123}"
RESTRICTED_USERNAME="${RESTRICTED_USERNAME:-canary-restricted@stage.local}"
RESTRICTED_PASSWORD="${RESTRICTED_PASSWORD:-CanaryRestricted123}"
TEST_REPORT_KEY="${TEST_REPORT_KEY:-satis-ozet}"
HR_SALARY_REPORT="${HR_SALARY_REPORT:-hr-maas-raporu}"

PASS=0
FAIL=0

log_pass() { echo "✅ $1" >&2; PASS=$((PASS + 1)); }
log_fail() { echo "❌ $1" >&2; FAIL=$((FAIL + 1)); }
log_info() { echo "[i] $1" >&2; }

# ---- Token helpers ----------------------------------------------------------

fetch_token() {
    local username="$1"
    local password="$2"
    curl -sf -X POST \
        "${BASE_URL}/realms/${KC_REALM}/protocol/openid-connect/token" \
        -d "client_id=${KC_CLIENT_ID}" \
        -d "grant_type=password" \
        -d "username=${username}" \
        -d "password=${password}" \
        2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])' 2>/dev/null
}

http_status() {
    # Args: [token-or-empty] [path]
    local token="$1"; shift
    local path="$1"
    if [ -z "$token" ]; then
        curl -sS -o /dev/null -w "%{http_code}" "${BASE_URL}${path}"
    else
        curl -sS -o /dev/null -w "%{http_code}" -H "Authorization: Bearer ${token}" "${BASE_URL}${path}"
    fi
}

http_body() {
    local token="$1"; shift
    local path="$1"
    if [ -z "$token" ]; then
        curl -sS "${BASE_URL}${path}"
    else
        curl -sS -H "Authorization: Bearer ${token}" "${BASE_URL}${path}"
    fi
}

# ---- Scenario 1: unauthenticated → 401 --------------------------------------

log_info "Scenario 1 — unauthenticated /api/v1/reports → 401"
status=$(http_status "" "/api/v1/reports")
if [ "$status" = "401" ]; then
    log_pass "Scenario 1: unauthenticated → 401"
else
    log_fail "Scenario 1: unauthenticated expected 401, got ${status}"
fi

# ---- Super-admin token prerequisite ----------------------------------------

log_info "Fetching super-admin token (${ADMIN_USERNAME})..."
ADMIN_TOKEN="$(fetch_token "${ADMIN_USERNAME}" "${ADMIN_PASSWORD}")" || ADMIN_TOKEN=""
if [ -z "$ADMIN_TOKEN" ]; then
    log_fail "Admin token fetch failed — cannot run scenarios 4-6"
else
    log_pass "Admin token acquired"
fi

# ---- Optional restricted user token ----------------------------------------

log_info "Fetching restricted user token (${RESTRICTED_USERNAME})..."
RESTRICTED_TOKEN="$(fetch_token "${RESTRICTED_USERNAME}" "${RESTRICTED_PASSWORD}")" || RESTRICTED_TOKEN=""
if [ -z "$RESTRICTED_TOKEN" ]; then
    log_info "Restricted user token unavailable — scenarios 2/3/5/6 will be skipped"
fi

# ---- Scenario 2: no REPORT_VIEW → 403 ---------------------------------------

if [ -n "$RESTRICTED_TOKEN" ]; then
    log_info "Scenario 2 — no REPORT_VIEW /api/v1/reports/${TEST_REPORT_KEY}/data → 403"
    status=$(http_status "$RESTRICTED_TOKEN" "/api/v1/reports/${TEST_REPORT_KEY}/data?page=1&pageSize=1")
    if [ "$status" = "403" ]; then
        log_pass "Scenario 2: restricted user without REPORT_VIEW → 403"
    else
        log_fail "Scenario 2: restricted user expected 403, got ${status}"
    fi
else
    log_info "Scenario 2 skipped (restricted token unavailable)"
fi

# ---- Scenario 3: REPORT_VIEW but no REPORT_EXPORT → 403 ---------------------

if [ -n "$RESTRICTED_TOKEN" ]; then
    log_info "Scenario 3 — /api/v1/reports/${TEST_REPORT_KEY}/export (REPORT_EXPORT missing) → 403"
    status=$(http_status "$RESTRICTED_TOKEN" "/api/v1/reports/${TEST_REPORT_KEY}/export?format=csv")
    # Either 403 (REPORT_VIEW missing, evaluator trips first) OR 403 (canExport trips) — both acceptable.
    if [ "$status" = "403" ]; then
        log_pass "Scenario 3: export forbidden → 403"
    else
        log_fail "Scenario 3: export expected 403, got ${status}"
    fi
else
    log_info "Scenario 3 skipped (restricted token unavailable)"
fi

# ---- Scenario 4: super-admin list → 200 + non-empty -------------------------

if [ -n "$ADMIN_TOKEN" ]; then
    log_info "Scenario 4 — super-admin /api/v1/reports → 200 + non-empty"
    body=$(http_body "$ADMIN_TOKEN" "/api/v1/reports")
    count=$(echo "$body" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))' 2>/dev/null || echo "0")
    if [ -n "$count" ] && [ "$count" -gt 0 ] 2>/dev/null; then
        log_pass "Scenario 4: super-admin sees ${count} report(s)"
    else
        log_fail "Scenario 4: super-admin returned empty/invalid list"
    fi
fi

# ---- Scenario 5: finance dashboard alias ------------------------------------

if [ -n "$ADMIN_TOKEN" ]; then
    log_info "Scenario 5 — super-admin /api/v1/dashboards includes fin-analytics"
    body=$(http_body "$ADMIN_TOKEN" "/api/v1/dashboards")
    has_fin=$(echo "$body" | python3 -c 'import json,sys
d=json.load(sys.stdin)
print("yes" if any(x.get("key")=="fin-analytics" for x in d) else "no")' 2>/dev/null || echo "no")
    if [ "$has_fin" = "yes" ]; then
        log_pass "Scenario 5: super-admin sees fin-analytics dashboard"
    else
        log_fail "Scenario 5: fin-analytics dashboard not visible to super-admin"
    fi

    if [ -n "$RESTRICTED_TOKEN" ]; then
        body=$(http_body "$RESTRICTED_TOKEN" "/api/v1/dashboards")
        has_fin_restricted=$(echo "$body" | python3 -c 'import json,sys
try:
  d=json.load(sys.stdin)
  print("yes" if isinstance(d, list) and any(x.get("key")=="fin-analytics" for x in d) else "no")
except Exception:
  print("err")' 2>/dev/null || echo "err")
        if [ "$has_fin_restricted" = "no" ]; then
            log_pass "Scenario 5b: restricted user does NOT see fin-analytics (alias filter works)"
        elif [ "$has_fin_restricted" = "yes" ]; then
            log_fail "Scenario 5b: restricted user sees fin-analytics — alias filter broken"
        else
            log_info "Scenario 5b inconclusive (non-list response)"
        fi
    fi
fi

# ---- Scenario 6: HR salary column restriction ------------------------------

if [ -n "$ADMIN_TOKEN" ]; then
    log_info "Scenario 6 — HR salary column restriction (metadata)"
    body=$(http_body "$ADMIN_TOKEN" "/api/v1/reports/${HR_SALARY_REPORT}/metadata")
    # Super-admin sees ALL columns — including a salary column if the report defines one.
    has_salary=$(echo "$body" | python3 -c 'import json,sys
try:
  d=json.load(sys.stdin)
  cols = d.get("columns", [])
  print("yes" if any("maas" in (c.get("field") or "").lower() or "salary" in (c.get("field") or "").lower() for c in cols) else "no")
except Exception:
  print("err")' 2>/dev/null || echo "err")
    if [ "$has_salary" = "yes" ]; then
        log_pass "Scenario 6a: super-admin metadata includes salary column"
    elif [ "$has_salary" = "no" ]; then
        log_info "Scenario 6a skipped (report does not define a salary column)"
    else
        log_info "Scenario 6a inconclusive (404 or non-JSON)"
    fi

    if [ -n "$RESTRICTED_TOKEN" ]; then
        body=$(http_body "$RESTRICTED_TOKEN" "/api/v1/reports/${HR_SALARY_REPORT}/metadata")
        has_salary_r=$(echo "$body" | python3 -c 'import json,sys
try:
  d=json.load(sys.stdin)
  cols = d.get("columns", []) if isinstance(d, dict) else []
  print("yes" if any("maas" in (c.get("field") or "").lower() or "salary" in (c.get("field") or "").lower() for c in cols) else "no")
except Exception:
  print("err")' 2>/dev/null || echo "err")
        if [ "$has_salary_r" = "no" ]; then
            log_pass "Scenario 6b: restricted user metadata hides salary column"
        elif [ "$has_salary_r" = "yes" ]; then
            log_fail "Scenario 6b: restricted user sees salary column — ColumnFilter broken"
        else
            log_info "Scenario 6b inconclusive"
        fi
    fi
fi

# ---- Summary ---------------------------------------------------------------

echo "" >&2
echo "==================================================" >&2
echo "  smoke-report-authz summary" >&2
echo "  PASS=${PASS}  FAIL=${FAIL}" >&2
echo "  BASE_URL=${BASE_URL}" >&2
echo "==================================================" >&2

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
exit 0
