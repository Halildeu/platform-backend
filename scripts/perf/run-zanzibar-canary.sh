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
# 1. Setup — idempotent seed + write-path verification
# ─────────────────────────────────────────────────────────────────────────────

if [[ "$SKIP_SETUP" == "1" ]]; then
  echo "[1/8] SETUP atlandı (SKIP_SETUP=1)"
  if [[ ! -f "$PERSONA_TOKENS" ]]; then
    echo "  ⚠️  Persona tokens dosyası yok: $PERSONA_TOKENS"
    echo "  → k6 fallback AUTH_TOKEN env'ine düşecek (local permitAll için ok)"
  fi
else
  echo "[1/8] SETUP: zanzibar-canary-setup.mjs"
  node "$REPO_ROOT/backend/scripts/ci/canary/zanzibar-canary-setup.mjs" \
    --output "$PERSONA_TOKENS" \
    2>&1 | tee "$REPORT_DIR/setup.log"
fi

# ─────────────────────────────────────────────────────────────────────────────
# 2. Restricted probe — pre-check
# ─────────────────────────────────────────────────────────────────────────────

if [[ "$SKIP_PROBE" == "1" ]]; then
  echo "[2/8] PROBE PRE atlandı (SKIP_PROBE=1)"
else
  echo "[2/8] PROBE PRE: zanzibar-restricted-probe.sh"
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
  echo "[3/8] ESTIMATE_ONLY run (k6 HTTP yok, decision floor projeksiyonu)"
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

echo "[4/8] k6 COLD phase"
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
# 5. k6 WARM phase — cold hemen sonrası, version bump YOK
# ─────────────────────────────────────────────────────────────────────────────

echo "[5/8] k6 WARM phase (cold bitişinden hemen sonra)"
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
# 6. Restricted probe — post-check
# ─────────────────────────────────────────────────────────────────────────────

if [[ "$SKIP_PROBE" == "1" ]]; then
  echo "[6/8] PROBE POST atlandı (SKIP_PROBE=1)"
else
  echo "[6/8] PROBE POST"
  if bash "$REPO_ROOT/backend/scripts/ci/canary/zanzibar-restricted-probe.sh" \
    2>&1 | tee "$REPORT_DIR/probe-post.log"; then
    echo "  ✅ probe-post PASS"
  else
    echo "  ❌ probe-post FAIL"
    exit 1
  fi
fi

# ─────────────────────────────────────────────────────────────────────────────
# 7. Prometheus metrics pull
# ─────────────────────────────────────────────────────────────────────────────

if [[ "$SKIP_METRICS" == "1" ]]; then
  echo "[7/8] METRICS PULL atlandı (SKIP_METRICS=1)"
else
  echo "[7/8] METRICS PULL: pull-grafana-metrics.mjs"
  node "$REPO_ROOT/backend/scripts/ci/canary/pull-grafana-metrics.mjs" \
    > "$REPORT_DIR/prom-metrics.json" \
    2> "$REPORT_DIR/prom-metrics.log" || {
      echo "  ⚠️  metrics pull başarısız — guardrail-check 'no data' ile fail edecek"
    }
fi

# ─────────────────────────────────────────────────────────────────────────────
# 8. Guardrail-check
# ─────────────────────────────────────────────────────────────────────────────

if [[ "$SKIP_METRICS" == "1" ]]; then
  echo "[8/8] GUARDRAIL atlandı (SKIP_METRICS=1)"
  echo ""
  echo "✅ Run tamamlandı (no-metrics mode): $REPORT_DIR"
  exit 0
fi

echo "[8/8] GUARDRAIL-CHECK (zanzibar-canary mode, phase=warm — strict cache miss)"
# CNS-20260416-001: --phase warm → guardrail cache miss threshold'u warm phase için
# strict uygulanır (cold phase olsaydı soften olurdu). PR-2'de cold+warm ayrı guardrail
# çağrısı planlanıyor (ayrı prom-cold.json / prom-warm.json artifacts sonrası).
if node "$REPO_ROOT/backend/scripts/ci/canary/guardrail-check.mjs" \
  --zanzibar-canary \
  --phase warm \
  --metrics "$REPORT_DIR/prom-metrics.json" \
  2>&1 | tee "$REPORT_DIR/guardrail.log"; then
  echo ""
  echo "✅ GUARDRAIL PASS: $REPORT_DIR"
else
  echo ""
  echo "❌ GUARDRAIL FAIL: $REPORT_DIR"
  exit 1
fi
