#!/usr/bin/env node
/**
 * Canary metric collector.
 *
 * Usage examples:
 *   node scripts/ci/canary/pull-grafana-metrics.mjs \
 *     --sample scripts/ci/canary/samples/stage10.json \
 *     --output scripts/ci/canary/generated-stage10.json
 *
 *   node scripts/ci/canary/pull-grafana-metrics.mjs \
 *     --prom-url https://grafana.example.com/api/datasources/proxy/UID \
 *     --api-key $GRAFANA_API_KEY \
 *     --ttfb-query 'histogram_quantile(0.95, rate(app_ttfb_bucket[5m])) * 1000' \
 *     --error-query 'sum(rate(app_request_errors_total[5m])) / sum(rate(app_requests_total[5m])) * 100' \
 *     --sentry-query 'sum(rate(app_sentry_errors_total[5m])) / sum(rate(app_requests_total[5m])) * 100' \
 *     --audit-filter-query 'sum(rate({app="permission-service"} |= "filter[" [5m]))' \
 *     --audit-total-query 'sum(rate({app="permission-service"} |= "GET /api/audit/events" [5m]))' \
 *     --output scripts/ci/canary/generated-stage50.json
 */

import fs from 'node:fs';
import path from 'node:path';

const parseArgs = () => {
  const entries = new Map();
  const cliArgs = process.argv.slice(2);
  for (let i = 0; i < cliArgs.length; i += 1) {
    const flag = cliArgs[i];
    if (!flag.startsWith('--')) continue;
    const next = cliArgs[i + 1];
    if (next && !next.startsWith('--')) {
      entries.set(flag, next);
      i += 1;
    } else {
      entries.set(flag, 'true');
    }
  }
  return entries;
};

const args = parseArgs();

const resolveArg = (flag, fallback) => {
  if (args.has(flag)) {
    return args.get(flag);
  }
  return fallback;
};

const toNumber = (value, label) => {
  const num = Number(value);
  if (!Number.isFinite(num)) {
    throw new Error(`${label} sayısal olmalıdır; gelen değer: ${value}`);
  }
  return num;
};

// CNS-20260416-002 (PR-2): --phase cold|warm flag ile output filename auto-resolve.
// Wrapper cold phase sonrası ve warm phase sonrası ayrı çağrı yapar → ayrı artifact
// (prom-cold.json / prom-warm.json). Eski --output yolu geriye dönük korunur.
const phase = (resolveArg('--phase', process.env.CANARY_PHASE ?? '') || '').toLowerCase();
const defaultOutputName = phase
  ? `prom-${phase}.json`
  : 'scripts/ci/canary/canary-metrics.json';
const outputPath = path.resolve(
  process.cwd(),
  resolveArg('--output', defaultOutputName),
);
const samplePath = resolveArg('--sample', process.env.CANARY_SAMPLE_FILE);
const windowMinutes = toNumber(
  resolveArg('--window-minutes', process.env.CANARY_WINDOW_MINUTES ?? '30'),
  'windowMinutes',
);
const nowIso = new Date().toISOString();

if (samplePath) {
  const absoluteSample = path.resolve(process.cwd(), samplePath);
  if (!fs.existsSync(absoluteSample)) {
    throw new Error(`Sample dosyası bulunamadı: ${absoluteSample}`);
  }
  const sampleData = JSON.parse(fs.readFileSync(absoluteSample, 'utf8'));
  const merged = {
    windowMinutes,
    timestamp: nowIso,
    ...sampleData,
  };
  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, JSON.stringify(merged, null, 2));
  console.log(`ℹ️  Sample metrics ${absoluteSample} -> ${outputPath}`);
  process.exit(0);
}

const promBase = resolveArg('--prom-url', process.env.CANARY_PROM_URL);
if (!promBase) {
  throw new Error('Grafana/Prometheus taban URL’si (--prom-url veya CANARY_PROM_URL) zorunludur.');
}

const buildHeaders = () => {
  const headers = { Accept: 'application/json' };
  const apiKey = resolveArg('--api-key', process.env.GRAFANA_API_KEY ?? process.env.CANARY_API_KEY);
  if (apiKey) {
    headers.Authorization = apiKey.startsWith('Bearer ') ? apiKey : `Bearer ${apiKey}`;
  }
  return headers;
};

const headers = buildHeaders();

const fetchScalar = async (baseUrl, expr, timestamp) => {
  if (!expr) {
    throw new Error('Prometheus sorgusu belirtilmelidir.');
  }
  const url = new URL('/api/v1/query', baseUrl);
  url.searchParams.set('query', expr);
  if (timestamp) {
    url.searchParams.set('time', String(timestamp));
  }
  const response = await fetch(url, {
    method: 'GET',
    headers,
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`Sorgu başarısız (${response.status}): ${text}`);
  }
  const payload = await response.json();
  const value = payload?.data?.result?.[0]?.value?.[1];
  if (value === undefined || value === null) {
    return 0;
  }
  const num = Number(value);
  if (!Number.isFinite(num)) {
    throw new Error(`Sorgu sonucu sayısal değil: ${value}`);
  }
  return num;
};

const queryTimestamp = Number.parseInt(
  resolveArg('--query-time', ''),
  10,
) || Math.floor(Date.now() / 1000);

const fetchMetric = async (flag, scaleDefault, options = {}) => {
  const expr = resolveArg(`--${flag}-query`, process.env[`CANARY_${flag.toUpperCase()}_QUERY`]);
  if (!expr) {
    throw new Error(`'${flag}' metriği için sorgu (--${flag}-query) belirtilmeli`);
  }
  const url =
    resolveArg(`--${flag}-url`, process.env[`CANARY_${flag.toUpperCase()}_URL`]) ?? promBase;
  const scale = Number.parseFloat(
    resolveArg(`--${flag}-scale`, process.env[`CANARY_${flag.toUpperCase()}_SCALE`] ?? scaleDefault),
  );
  const raw = await fetchScalar(url, expr, queryTimestamp);
  return raw * scale;
};

const main = async () => {
  const ttfbMs = await fetchMetric('ttfb', '1');
  const errorRate = await fetchMetric('error', '1');
  const sentryRate = await fetchMetric('sentry', '1');

  const auditFilterExpr = resolveArg('--audit-filter-query', process.env.CANARY_AUDIT_FILTER_QUERY);
  const auditTotalExpr = resolveArg('--audit-total-query', process.env.CANARY_AUDIT_TOTAL_QUERY);
  if (!auditFilterExpr || !auditTotalExpr) {
    throw new Error('Audit filter oranı için hem --audit-filter-query hem --audit-total-query gerekir.');
  }
  const auditBase =
    resolveArg('--audit-url', process.env.CANARY_AUDIT_URL) ?? promBase;
  const filterCount = await fetchScalar(auditBase, auditFilterExpr, queryTimestamp);
  const totalCount = await fetchScalar(auditBase, auditTotalExpr, queryTimestamp);
  const auditUsage = totalCount > 0 ? (filterCount / totalCount) * 100 : 0;

  // Zanzibar authz metrics (optional — skipped if queries not provided)
  const authzCheckP95Expr = resolveArg('--authz-check-query', process.env.CANARY_AUTHZ_CHECK_QUERY);
  const authzDenyExpr = resolveArg('--authz-deny-query', process.env.CANARY_AUTHZ_DENY_QUERY);
  const authzErrorExpr = resolveArg('--authz-error-query', process.env.CANARY_AUTHZ_ERROR_QUERY);
  const authzCacheMissExpr = resolveArg('--authz-cache-miss-query', process.env.CANARY_AUTHZ_CACHE_MISS_QUERY);

  // CNS-20260416-001 B4 fix: guardrail-check zanzibar-canary modda
  // authz_decisions_total >= 1000 (NO_SIGNAL) ve openfga_circuit_breaker_state == 0 (CLOSED)
  // metric'lerini ZORUNLU kılıyor. Bunları collector'da ek argüman/env ile toplanabilir yap.
  const authzDecisionsExpr = resolveArg('--authz-decisions-query', process.env.CANARY_AUTHZ_DECISIONS_QUERY);
  const openFgaCbExpr = resolveArg('--openfga-cb-query', process.env.CANARY_OPENFGA_CB_QUERY);

  // CNS-20260416-002 (PR-2): Ek operasyonel guardrail metric'leri (Rev 22 backlog #7-9).
  // Tüm optional — query basılmazsa payload'a eklenmez.
  //   authz_me_p95_ms                     → /authz/me latency (persona snapshot sağlığı)
  //   tuple_sync_outbox_pending_total     → birikmiş outbox (drift guard)
  //   tuple_sync_outbox_oldest_age_s      → en eski pending entry yaşı (sync gecikme guard)
  //   tuple_sync_outbox_failed_total      → dead-letter counter (silent failure guard)
  //   openfga_up                          → target=1 (down tespiti için)
  const authzMeP95Expr = resolveArg('--authz-me-p95-query', process.env.CANARY_AUTHZ_ME_P95_QUERY);
  const outboxPendingExpr = resolveArg('--outbox-pending-query', process.env.CANARY_OUTBOX_PENDING_QUERY);
  const outboxOldestAgeExpr = resolveArg('--outbox-oldest-age-query', process.env.CANARY_OUTBOX_OLDEST_AGE_QUERY);
  const outboxFailedExpr = resolveArg('--outbox-failed-query', process.env.CANARY_OUTBOX_FAILED_QUERY);
  const openFgaUpExpr = resolveArg('--openfga-up-query', process.env.CANARY_OPENFGA_UP_QUERY);

  const authzBase = resolveArg('--authz-url', process.env.CANARY_AUTHZ_URL) ?? promBase;
  const authzCheckP95 = authzCheckP95Expr
    ? await fetchScalar(authzBase, authzCheckP95Expr, queryTimestamp)
    : undefined;
  const authzDenyRate = authzDenyExpr
    ? await fetchScalar(authzBase, authzDenyExpr, queryTimestamp)
    : undefined;
  const authzErrorRate = authzErrorExpr
    ? await fetchScalar(authzBase, authzErrorExpr, queryTimestamp)
    : undefined;
  const authzCacheMissRate = authzCacheMissExpr
    ? await fetchScalar(authzBase, authzCacheMissExpr, queryTimestamp)
    : undefined;
  const authzDecisionsTotal = authzDecisionsExpr
    ? await fetchScalar(authzBase, authzDecisionsExpr, queryTimestamp)
    : undefined;
  const openFgaCbState = openFgaCbExpr
    ? await fetchScalar(authzBase, openFgaCbExpr, queryTimestamp)
    : undefined;
  const authzMeP95 = authzMeP95Expr
    ? await fetchScalar(authzBase, authzMeP95Expr, queryTimestamp)
    : undefined;
  const outboxPending = outboxPendingExpr
    ? await fetchScalar(authzBase, outboxPendingExpr, queryTimestamp)
    : undefined;
  const outboxOldestAge = outboxOldestAgeExpr
    ? await fetchScalar(authzBase, outboxOldestAgeExpr, queryTimestamp)
    : undefined;
  const outboxFailed = outboxFailedExpr
    ? await fetchScalar(authzBase, outboxFailedExpr, queryTimestamp)
    : undefined;
  const openFgaUp = openFgaUpExpr
    ? await fetchScalar(authzBase, openFgaUpExpr, queryTimestamp)
    : undefined;

  const payload = {
    ttfb_p95_ms: Number(ttfbMs.toFixed(2)),
    error_rate_pct: Number(errorRate.toFixed(3)),
    sentry_error_rate_pct: Number(sentryRate.toFixed(3)),
    audit_filter_usage_pct: Number(auditUsage.toFixed(3)),
    ...(authzCheckP95 !== undefined && { authz_check_p95_ms: Number(authzCheckP95.toFixed(2)) }),
    ...(authzDenyRate !== undefined && { authz_deny_rate_pct: Number(authzDenyRate.toFixed(3)) }),
    ...(authzErrorRate !== undefined && { authz_error_rate_pct: Number(authzErrorRate.toFixed(3)) }),
    ...(authzCacheMissRate !== undefined && { authz_cache_miss_rate_pct: Number(authzCacheMissRate.toFixed(3)) }),
    ...(authzDecisionsTotal !== undefined && { authz_decisions_total: Math.round(authzDecisionsTotal) }),
    ...(openFgaCbState !== undefined && { openfga_circuit_breaker_state: Math.round(openFgaCbState) }),
    ...(authzMeP95 !== undefined && { authz_me_p95_ms: Number(authzMeP95.toFixed(2)) }),
    ...(outboxPending !== undefined && { tuple_sync_outbox_pending_total: Math.round(outboxPending) }),
    ...(outboxOldestAge !== undefined && { tuple_sync_outbox_oldest_age_s: Number(outboxOldestAge.toFixed(1)) }),
    ...(outboxFailed !== undefined && { tuple_sync_outbox_failed_total: Math.round(outboxFailed) }),
    ...(openFgaUp !== undefined && { openfga_up: Math.round(openFgaUp) }),
    ...(phase && { phase }),
    windowMinutes,
    timestamp: nowIso,
  };

  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, JSON.stringify(payload, null, 2));
  console.log(`✅ Metrics toplandı: ${outputPath}`);
  console.log(payload);
};

main().catch((error) => {
  console.error('❌ Metrics toplama hatası:', error.message);
  process.exitCode = 1;
});
