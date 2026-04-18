#!/usr/bin/env bash
#
# run-zanzibar-canary.sh
#
# Zanzibar synthetic canary orchestration wrapper.
# CNS-20260415-004 (Codex) tasarımı — sequential akış:
#   setup → probe pre → [ESTIMATE_ONLY exit] → k6 cold → k6 warm → probe post → metrics pull → guardrail-check
#
# Cold ve warm arasında version bump YOK (cache state korunur; CNS-004 Q3 kuralı).
# k6 bash'e shell-out etmez; orchestration burada (CNS-004 Q5).
#
# Mod:
#   ESTIMATE_ONLY=1  → k6 ESTIMATE_ONLY koşar, cold/warm/probe atlanır
#   LOCAL_PERMIT_ALL=1 → k6 script intentional deny'leri expected=allow'a override eder
#                        (OpenFGA disabled local profile smoke için; staging'de BASMAZ)
#   SKIP_SETUP=1     → setup script atlanır (seed zaten var, tekrar run)
#   SKIP_PROBE=1     → restricted probe atlanır (auth-disabled lokal için)
#   SKIP_METRICS=1   → Prometheus pull + guardrail-check atlanır (local smoke)
#   REQUIRE_V2_OPS=0 → v2 ops metric'leri (authz_me_p95, outbox_*, openfga_up)
#                      silent-skip moduna al; default=1 (Evidence PASS strict)
#
# Env:
#   BASE_URL (default http://localhost:8090)
#   RUN_ID (default timestamp)
#   REPORT_DIR (default .cache/reports/zanzibar-canary/$RUN_ID)
#   CANARY_PASSWORD (default CanaryPass123!) — setup ile aynı
#
# Guardrail zanzibar-canary modu için ZORUNLU metric query env'leri
# (auth-enabled staging run'ı için CANARY_PROM_URL + aşağıdakiler set edilmeli;
#  local smoke için SKIP_METRICS=1 geçilir):
#   CANARY_PROM_URL='http://prometheus:9090'
#   CANARY_AUTHZ_CHECK_QUERY='histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri=~"/api/v1/authz/check|/api/v1/authz/batch-check"}[5m]))) * 1000'
#   CANARY_AUTHZ_DENY_QUERY='sum(rate(authz_decisions_total{allowed="false"}[5m])) / clamp_min(sum(rate(authz_decisions_total[5m])), 0.001) * 100'
#   CANARY_AUTHZ_ERROR_QUERY='sum(rate(http_server_requests_seconds_count{uri=~"/api/v1/authz/.*",status=~"5.."}[5m])) / clamp_min(sum(rate(http_server_requests_seconds_count{uri=~"/api/v1/authz/.*"}[5m])), 0.001) * 100'
#   CANARY_AUTHZ_CACHE_MISS_QUERY='authz_cache_miss_count{cache="scope_context"} / clamp_min(authz_cache_hit_count{cache="scope_context"} + authz_cache_miss_count{cache="scope_context"}, 1) * 100'
#   CANARY_AUTHZ_DECISIONS_QUERY='sum(increase(authz_decisions_total[35m]))'   # NO_SIGNAL guard (>= 1000)
#   CANARY_OPENFGA_CB_QUERY='max(openfga_circuit_breaker_state)'               # 0=CLOSED
#
# Çıktılar ($REPORT_DIR altında):
#   setup.log
#   persona-tokens.json (setup tarafından)
#   probe-pre.log probe-post.log
#   cold-k6-summary.json warm-k6-summary.json
#   estimate.json (ESTIMATE_ONLY modda)
#   prom-metrics.json
#   guardrail.log

set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
# Setup
# ─────────────────────────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

BASE_URL="${BASE_URL:-http://localhost:8090}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
REPORT_DIR="${REPORT_DIR:-$REPO_ROOT/.cache/reports/zanzibar-canary/$RUN_ID}"
PERSONA_TOKENS="$REPORT_DIR/persona-tokens.json"

ESTIMATE_ONLY="${ESTIMATE_ONLY:-0}"
LOCAL_PERMIT_ALL="${LOCAL_PERMIT_ALL:-0}"
SKIP_SETUP="${SKIP_SETUP:-0}"
SKIP_PROBE="${SKIP_PROBE:-0}"
SKIP_METRICS="${SKIP_METRICS:-0}"

mkdir -p "$REPORT_DIR"

echo "━━━ Zanzibar Synthetic Canary Run ━━━"
echo "  RUN_ID:      $RUN_ID"
echo "  BASE_URL:    $BASE_URL"
echo "  REPORT_DIR:  $REPORT_DIR"
echo "  ESTIMATE:    $ESTIMATE_ONLY"
echo "  LOCAL_PERMIT_ALL: $LOCAL_PERMIT_ALL"
echo "  SKIP_SETUP:  $SKIP_SETUP"
echo "  SKIP_PROBE:  $SKIP_PROBE"
echo "  SKIP_METRICS:$SKIP_METRICS"
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# 0. Preflight — fail-closed on tooling + env + reachability gaps
# ─────────────────────────────────────────────────────────────────────────────
# 2026-04-18 Codex Thread 5 finding #5 (preflight eksik): the canary wrapper
# was starting the expensive k6/setup path before verifying the host had the
# required tools or that the target URLs were reachable. Failures showed up
# mid-stream as node/k6/jq missing errors or curl connection refused, masking
# the actual drift. This preflight block runs upfront, fail-closed, producing
# a clear error BEFORE any orchestration work.

echo "[0/10] PREFLIGHT"
preflight_fail=0

# Step A — Required CLI tools. SKIP_METRICS / SKIP_PROBE don't waive node/k6
# because setup + k6 k6-zanzibar-check.js are the canary core.
required_tools=(bash curl python3)
if [[ "$SKIP_SETUP" != "1" ]]; then
  required_tools+=(node)
fi
if [[ "$ESTIMATE_ONLY" != "1" ]] || [[ "$SKIP_SETUP" != "1" ]]; then
  required_tools+=(k6)
fi
if [[ "$SKIP_METRICS" != "1" ]]; then
  required_tools+=(jq)
fi
missing_tools=()
for t in "${required_tools[@]}"; do
  command -v "$t" >/dev/null 2>&1 || missing_tools+=("$t")
done
if (( ${#missing_tools[@]} > 0 )); then
  echo "  ❌ Required tools missing: ${missing_tools[*]}" >&2
  echo "  → Install before run. For staging Ubuntu:" >&2
  echo "     apt install -y nodejs jq" >&2
  echo "     # k6: https://grafana.com/docs/k6/latest/set-up/install-k6/" >&2
  preflight_fail=1
fi

# Step B — Target reachability. Check curl exit code first (DNS failure,
# connection refused, timeout all produce non-zero), then HTTP status (any
# status = route reachable, even 401/404/500). --max-redirs 0 prevents
# multi-redirect chains that concat zeros in %{http_code} output.
if base_status="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 --connect-timeout 3 --max-redirs 0 "$BASE_URL" 2>/dev/null)"; then
  echo "  ✅ BASE_URL reachable ($base_status): $BASE_URL"
else
  curl_exit=$?
  echo "  ❌ BASE_URL unreachable (curl exit=$curl_exit): $BASE_URL" >&2
  echo "  → Verify service up + network (DNS? firewall? port?). Expected HTTP code, got connect fail." >&2
  preflight_fail=1
fi

# Step C — Prometheus reachability (only when metrics pull enabled)
if [[ "$SKIP_METRICS" != "1" ]]; then
  prom_url="${CANARY_PROM_URL:-http://localhost:9090}"
  if prom_health="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 --connect-timeout 3 --max-redirs 0 "${prom_url%/}/-/healthy" 2>/dev/null)"; then
    if [[ "$prom_health" == "200" ]]; then
      echo "  ✅ Prometheus reachable: ${prom_url}"
    else
      echo "  ❌ Prometheus /-/healthy returned ${prom_health}: ${prom_url}" >&2
      preflight_fail=1
    fi
  else
    curl_exit=$?
    echo "  ❌ Prometheus unreachable (curl exit=$curl_exit): ${prom_url}" >&2
    echo "  → Set CANARY_PROM_URL or SKIP_METRICS=1 for local smoke." >&2
    preflight_fail=1
  fi
fi

# Step D — OpenFGA store id (setup expectation; skip when SKIP_SETUP)
if [[ "$SKIP_SETUP" != "1" ]]; then
  if [[ -z "${OPENFGA_STORE_ID:-}" ]]; then
    echo "  ❌ OPENFGA_STORE_ID env missing (required by setup script)" >&2
    echo "  → Export OPENFGA_STORE_ID before run (staging canonical env has this key)." >&2
    preflight_fail=1
  else
    echo "  ✅ OPENFGA_STORE_ID set: ${OPENFGA_STORE_ID:0:20}..."
  fi
fi

# Step E — Canary client credentials (setup script needs these for KC tokens)
if [[ "$SKIP_SETUP" != "1" ]]; then
  if [[ -z "${KC_CANARY_CLIENT_SECRET:-}" ]] && [[ -z "${SERVICE_CLIENT_USER_SERVICE_SECRET:-}" ]]; then
    echo "  ⚠️  Neither KC_CANARY_CLIENT_SECRET nor SERVICE_CLIENT_USER_SERVICE_SECRET set" >&2
    echo "     → Setup may fall back to direct-grants if KC client allows; check setup.log if setup fails." >&2
    # WARN only — setup script has its own fallback logic
  fi
fi

if (( preflight_fail > 0 )); then
  echo ""
  echo "[preflight] FAIL — ${preflight_fail} blocker(s). Aborting before expensive orchestration." >&2
  exit 1
fi
echo "[preflight] PASS"
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# 1. Setup — idempotent seed + write-path verification
# ─────────────────────────────────────────────────────────────────────────────

if [[ "$SKIP_SETUP" == "1" ]]; then
  echo "[1/10] SETUP atlandı (SKIP_SETUP=1)"
  if [[ ! -f "$PERSONA_TOKENS" ]]; then
    echo "  ⚠️  Persona tokens dosyası yok: $PERSONA_TOKENS"
    echo "  → k6 fallback AUTH_TOKEN env'ine düşecek (local permitAll için ok)"
  fi
else
  echo "[1/10] SETUP: zanzibar-canary-setup.mjs"
  node "$REPO_ROOT/backend/scripts/ci/canary/zanzibar-canary-setup.mjs" \
    --output "$PERSONA_TOKENS" \
    2>&1 | tee "$REPORT_DIR/setup.log"
fi

# ─────────────────────────────────────────────────────────────────────────────
# 2. Restricted probe — pre-check
# ─────────────────────────────────────────────────────────────────────────────

if [[ "$SKIP_PROBE" == "1" ]]; then
  echo "[2/10] PROBE PRE atlandı (SKIP_PROBE=1)"
else
  echo "[2/10] PROBE PRE: zanzibar-restricted-probe.sh"
  if bash "$REPO_ROOT/backend/scripts/ci/canary/zanzibar-restricted-probe.sh" \
    2>&1 | tee "$REPORT_DIR/probe-pre.log"; then
    echo "  ✅ probe-pre PASS"
  else
    echo "  ❌ probe-pre FAIL — abort"
    exit 1
  fi
fi

# ─────────────────────────────────────────────────────────────────────────────
# 3. ESTIMATE_ONLY fast path
# ─────────────────────────────────────────────────────────────────────────────

if [[ "$ESTIMATE_ONLY" == "1" ]]; then
  echo "[3/10] ESTIMATE_ONLY run (k6 HTTP yok, decision floor projeksiyonu)"
  ESTIMATE_ONLY=1 BASE_URL="$BASE_URL" PHASE=estimate \
  LOCAL_PERMIT_ALL="$LOCAL_PERMIT_ALL" \
  SUMMARY_PATH="$REPORT_DIR/estimate.json" \
    k6 run \
      "$REPO_ROOT/backend/scripts/perf/k6-zanzibar-check.js" \
    2>&1 | tee "$REPORT_DIR/estimate.log"
  echo ""
  echo "✅ ESTIMATE_ONLY tamamlandı: $REPORT_DIR"
  exit 0
fi

# ─────────────────────────────────────────────────────────────────────────────
# 4. k6 COLD phase
# ─────────────────────────────────────────────────────────────────────────────

echo "[4/10] k6 COLD phase"
PERSONA_TOKENS_JSON=""
if [[ -f "$PERSONA_TOKENS" ]]; then
  PERSONA_TOKENS_JSON="$(jq -c '.tokens // {}' "$PERSONA_TOKENS")"
fi

PHASE=cold \
BASE_URL="$BASE_URL" \
PERSONA_TOKENS_JSON="$PERSONA_TOKENS_JSON" \
LOCAL_PERMIT_ALL="$LOCAL_PERMIT_ALL" \
SUMMARY_PATH="$REPORT_DIR/cold-k6-summary.json" \
k6 run \
  "$REPO_ROOT/backend/scripts/perf/k6-zanzibar-check.js" \
2>&1 | tee "$REPORT_DIR/cold-k6.log"

COLD_END_TS="$(date +%s)"

# ─────────────────────────────────────────────────────────────────────────────
# 4b. Metrics pull COLD (CNS-20260416-002: cold phase snapshot)
# ─────────────────────────────────────────────────────────────────────────────

if [[ "$SKIP_METRICS" == "1" ]]; then
  echo "[4b] METRICS PULL cold atlandı (SKIP_METRICS=1)"
else
  echo "[4b] METRICS PULL cold (phase=cold, query-time=$COLD_END_TS)"
  # CNS-20260416-002 B5 fix: --output ile dosya kayıt, stdout log'a.
  # PR-1'deki pattern (> prom-metrics.json) broken idi: collector kendi outputPath'ine
  # JSON yazıyor, stdout'a console.log payload basıyor → stdout JSON değil.
  node "$REPO_ROOT/backend/scripts/ci/canary/pull-grafana-metrics.mjs" \
    --phase cold \
    --output "$REPORT_DIR/prom-cold.json" \
    --query-time "$COLD_END_TS" \
    2>&1 | tee "$REPORT_DIR/prom-cold-pull.log" || {
      echo "  ⚠️  cold metrics pull başarısız — guardrail-check cold 'no data' ile fail edecek"
    }
fi

# ─────────────────────────────────────────────────────────────────────────────
# 5. k6 WARM phase — cold hemen sonrası, version bump YOK
# ─────────────────────────────────────────────────────────────────────────────

echo "[5/10] k6 WARM phase (cold bitişinden hemen sonra)"
PHASE=warm \
BASE_URL="$BASE_URL" \
PERSONA_TOKENS_JSON="$PERSONA_TOKENS_JSON" \
LOCAL_PERMIT_ALL="$LOCAL_PERMIT_ALL" \
SUMMARY_PATH="$REPORT_DIR/warm-k6-summary.json" \
k6 run \
  "$REPO_ROOT/backend/scripts/perf/k6-zanzibar-check.js" \
2>&1 | tee "$REPORT_DIR/warm-k6.log"

WARM_END_TS="$(date +%s)"

# ─────────────────────────────────────────────────────────────────────────────
# 5b. Metrics pull WARM (CNS-20260416-002)
# ─────────────────────────────────────────────────────────────────────────────

if [[ "$SKIP_METRICS" == "1" ]]; then
  echo "[5b] METRICS PULL warm atlandı (SKIP_METRICS=1)"
else
  echo "[5b] METRICS PULL warm (phase=warm, query-time=$WARM_END_TS)"
  node "$REPO_ROOT/backend/scripts/ci/canary/pull-grafana-metrics.mjs" \
    --phase warm \
    --output "$REPORT_DIR/prom-warm.json" \
    --query-time "$WARM_END_TS" \
    2>&1 | tee "$REPORT_DIR/prom-warm-pull.log" || {
      echo "  ⚠️  warm metrics pull başarısız — guardrail-check warm 'no data' ile fail edecek"
    }
fi

# ─────────────────────────────────────────────────────────────────────────────
# 6. Restricted probe — post-check
# ─────────────────────────────────────────────────────────────────────────────

if [[ "$SKIP_PROBE" == "1" ]]; then
  echo "[6/10] PROBE POST atlandı (SKIP_PROBE=1)"
else
  echo "[6/10] PROBE POST"
  if bash "$REPO_ROOT/backend/scripts/ci/canary/zanzibar-restricted-probe.sh" \
    2>&1 | tee "$REPORT_DIR/probe-post.log"; then
    echo "  ✅ probe-post PASS"
  else
    echo "  ❌ probe-post FAIL"
    exit 1
  fi
fi

# ─────────────────────────────────────────────────────────────────────────────
# 7+8. Guardrail-check cold + warm (CNS-20260416-002: iki ayrı phase checker)
# ─────────────────────────────────────────────────────────────────────────────

if [[ "$SKIP_METRICS" == "1" ]]; then
  echo "[7+8] GUARDRAIL atlandı (SKIP_METRICS=1)"
  echo ""
  echo "✅ Run tamamlandı (no-metrics mode): $REPORT_DIR"
  exit 0
fi

COLD_GUARDRAIL_PASS=0
WARM_GUARDRAIL_PASS=0

# CNS-20260416-002 Codex tur 5 öneri: Evidence PASS modunda v2 ops metric'leri
# zorunlu (authz_me_p95, outbox_*, openfga_up eksikse violation). Wrapper canary
# run'ında hepsini strict istiyoruz; opt-out için REQUIRE_V2_OPS=0 env.
REQUIRE_V2_OPS="${REQUIRE_V2_OPS:-1}"
V2_OPS_FLAG=""
if [[ "$REQUIRE_V2_OPS" == "1" ]]; then
  V2_OPS_FLAG="--require-v2-ops"
fi

echo "[7/10] GUARDRAIL-CHECK cold (cache miss threshold SOFTEN)"
if [[ -f "$REPORT_DIR/prom-cold.json" ]]; then
  if node "$REPO_ROOT/backend/scripts/ci/canary/guardrail-check.mjs" \
    --zanzibar-canary \
    $V2_OPS_FLAG \
    --phase cold \
    --metrics "$REPORT_DIR/prom-cold.json" \
    2>&1 | tee "$REPORT_DIR/guardrail-cold.log"; then
    echo "  ✅ cold guardrail PASS"
    COLD_GUARDRAIL_PASS=1
  else
    echo "  ❌ cold guardrail FAIL"
  fi
else
  echo "  ⚠️  prom-cold.json yok (metrics pull cold başarısız) — cold guardrail skip"
fi

echo ""
echo "[8/10] GUARDRAIL-CHECK warm (cache miss threshold STRICT)"
if [[ -f "$REPORT_DIR/prom-warm.json" ]]; then
  if node "$REPO_ROOT/backend/scripts/ci/canary/guardrail-check.mjs" \
    --zanzibar-canary \
    $V2_OPS_FLAG \
    --phase warm \
    --metrics "$REPORT_DIR/prom-warm.json" \
    2>&1 | tee "$REPORT_DIR/guardrail-warm.log"; then
    echo "  ✅ warm guardrail PASS"
    WARM_GUARDRAIL_PASS=1
  else
    echo "  ❌ warm guardrail FAIL"
  fi
else
  echo "  ⚠️  prom-warm.json yok (metrics pull warm başarısız) — warm guardrail skip"
fi

echo ""
if [[ "$COLD_GUARDRAIL_PASS" == "1" && "$WARM_GUARDRAIL_PASS" == "1" ]]; then
  echo "✅ GUARDRAIL PASS (cold + warm): $REPORT_DIR"
  exit 0
else
  echo "❌ GUARDRAIL FAIL (cold=$COLD_GUARDRAIL_PASS warm=$WARM_GUARDRAIL_PASS): $REPORT_DIR"
  exit 1
fi
