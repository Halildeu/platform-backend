#!/usr/bin/env node
/**
 * Canary guardrail kontrol script'i.
 * Örnek kullanım:
 *   node scripts/ci/canary/guardrail-check.mjs --metrics scripts/ci/canary/samples/stage10.json --weight 10
 */
import fs from 'node:fs';
import path from 'node:path';

const parseArgs = () => {
  const args = process.argv.slice(2);
  const map = new Map();
  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i];
    if (arg.startsWith('--')) {
      const key = arg;
      const value = args[i + 1] && !args[i + 1].startsWith('--') ? args[i + 1] : 'true';
      if (value !== 'true') {
        i += 1;
      }
      map.set(key, value);
    }
  }
  return map;
};

const args = parseArgs();
const resolveArg = (flag, fallback) => {
  if (args.has(flag)) {
    return args.get(flag);
  }
  return fallback;
};

const metricsPath = path.resolve(
  process.cwd(),
  resolveArg('--metrics', 'scripts/ci/canary/canary-metrics.json'),
);
const weight = Number.parseInt(
  resolveArg('--weight', process.env.CANARY_WEIGHT ?? '10'),
  10,
);

const thresholds = {
  ttfb: Number.parseFloat(resolveArg('--ttfb', process.env.GUARDRAIL_TTFB_MS ?? '2000')),
  errorRate: Number.parseFloat(resolveArg('--error-rate', process.env.GUARDRAIL_ERROR_RATE ?? '2')),
  sentry: Number.parseFloat(resolveArg('--sentry', process.env.GUARDRAIL_SENTRY_RATE ?? '1')),
  auditFilterUsage: Number.parseFloat(resolveArg('--audit-filter-usage', process.env.GUARDRAIL_AUDIT_FILTER_USAGE_MIN_PCT ?? '20')),
  authzCheckP95: Number.parseFloat(resolveArg('--authz-check-p95', process.env.GUARDRAIL_AUTHZ_CHECK_P95_MS ?? '50')),
  authzDenyRate: Number.parseFloat(resolveArg('--authz-deny-rate', process.env.GUARDRAIL_AUTHZ_DENY_RATE ?? '10')),
  authzErrorRate: Number.parseFloat(resolveArg('--authz-error-rate', process.env.GUARDRAIL_AUTHZ_ERROR_RATE ?? '0.5')),
  authzCacheMiss: Number.parseFloat(resolveArg('--authz-cache-miss', process.env.GUARDRAIL_AUTHZ_CACHE_MISS ?? '50')),
  authzMinDecisions: Number.parseInt(resolveArg('--authz-min-decisions', process.env.GUARDRAIL_AUTHZ_MIN_DECISIONS ?? '1000'), 10),
  // CNS-20260415-004: circuit breaker state (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
  // Kabul edilebilir max = 0 (CLOSED). OPEN veya HALF_OPEN rollback tetikler.
  openFgaCbMax: Number.parseInt(resolveArg('--openfga-cb-max', process.env.GUARDRAIL_OPENFGA_CB_MAX ?? '0'), 10),
};

// CNS-20260415-004 Codex Q3: Cache miss rate threshold'u faz bazlı uygula.
// - cold phase'de miss rate yüksek beklenir (cache soğuk) → soften/skip
// - warm phase'de miss rate < 50% zorunlu (rollback eşiği)
// Aynı check binary tarafından çağrıldığında --phase cold|warm ile geçilir.
const phase = (resolveArg('--phase', process.env.GUARDRAIL_PHASE ?? '') || '').toLowerCase();
const phaseIsCold = phase === 'cold';
const phaseIsWarm = phase === 'warm';

// CNS-20260415-003 Codex uzlasisi: Zanzibar canary modunda authz metric'leri
// OPSIYONEL DEGIL, ZORUNLU. Mode --zanzibar-canary flag veya
// GUARDRAIL_ZANZIBAR_CANARY=true env ile aktiflesir.
// Aktifken:
// - authz_check_p95_ms, authz_deny_rate_pct, authz_error_rate_pct,
//   authz_cache_miss_rate_pct alanlari number olmali (yoksa violation)
// - authz_decisions_total >= authzMinDecisions (default 1000, yoksa NO_SIGNAL violation)
const zanzibarCanaryMode =
  args.has('--zanzibar-canary') ||
  (process.env.GUARDRAIL_ZANZIBAR_CANARY ?? '').toLowerCase() === 'true';

const readMetrics = (filePath) => {
  if (!fs.existsSync(filePath)) {
    throw new Error(`Metrics dosyası bulunamadı: ${filePath}`);
  }
  const raw = fs.readFileSync(filePath, 'utf8');
  try {
    return JSON.parse(raw);
  } catch (error) {
    throw new Error(`Metrics JSON parse edilemedi (${filePath}): ${(error).message}`);
  }
};

const metrics = readMetrics(metricsPath);

const violations = [];

if (typeof metrics.ttfb_p95_ms !== 'number') {
  throw new Error('metrics.ttfb_p95_ms alanı sayısal olmalı');
}
if (typeof metrics.error_rate_pct !== 'number') {
  throw new Error('metrics.error_rate_pct alanı sayısal olmalı');
}
['sentry_error_rate_pct', 'audit_filter_usage_pct'].forEach((key) => {
  if (typeof metrics[key] !== 'number') {
    throw new Error(`metrics.${key} alanı sayısal olmalı`);
  }
});

if (metrics.ttfb_p95_ms > thresholds.ttfb) {
  violations.push(
    `TTFB p95 ${metrics.ttfb_p95_ms}ms > eşik ${thresholds.ttfb}ms`,
  );
}
if (metrics.error_rate_pct > thresholds.errorRate) {
  violations.push(
    `Hata oranı ${metrics.error_rate_pct}% > eşik ${thresholds.errorRate}%`,
  );
}
if (metrics.sentry_error_rate_pct > thresholds.sentry) {
  violations.push(
    `Sentry error oranı ${metrics.sentry_error_rate_pct}% > eşik ${thresholds.sentry}%`,
  );
}
if (metrics.audit_filter_usage_pct < thresholds.auditFilterUsage) {
  violations.push(
    `Audit filter kullanımı ${metrics.audit_filter_usage_pct}% < eşik ${thresholds.auditFilterUsage}%`,
  );
}

// Zanzibar authz guardrails — default OPTIONAL (legacy behavior); Zanzibar canary
// mode'da ZORUNLU (CNS-003 Codex uzlasisi).
const authzRequired = zanzibarCanaryMode;

const checkAuthzMetric = (key, threshold, unit, label) => {
  const value = metrics[key];
  if (typeof value !== 'number') {
    if (authzRequired) {
      violations.push(
        `[zanzibar-canary] authz metric eksik: metrics.${key} — bu mode'da ZORUNLU`,
      );
    }
    return;
  }
  if (value > threshold) {
    violations.push(`${label} ${value}${unit} > eşik ${threshold}${unit}`);
  }
};

checkAuthzMetric('authz_check_p95_ms', thresholds.authzCheckP95, 'ms', 'AuthZ check p95');
checkAuthzMetric('authz_deny_rate_pct', thresholds.authzDenyRate, '%', 'AuthZ deny oranı');
checkAuthzMetric('authz_error_rate_pct', thresholds.authzErrorRate, '%', 'AuthZ error oranı');

// CNS-20260415-004 Codex Q3: cache miss rate cold phase'de soften
// - cold (cache soğuk) → miss rate yüksek doğal, violation YOK
// - warm (cache ısınmış) → threshold uygulanır
// - phase belirtilmediyse → legacy behavior (threshold uygula)
if (phaseIsCold) {
  const cacheMiss = metrics.authz_cache_miss_rate_pct;
  if (typeof cacheMiss === 'number') {
    console.log(`ℹ️  [cold phase] authz_cache_miss_rate_pct=${cacheMiss}% — threshold atlandı (cache soğuk, beklenen)`);
  }
} else {
  checkAuthzMetric('authz_cache_miss_rate_pct', thresholds.authzCacheMiss, '%', 'AuthZ cache miss oranı');
}

// CNS-20260415-004 Codex önerisi: OpenFGA circuit breaker state
// - metrics.openfga_circuit_breaker_state (servisler arası max)
// - 0 = CLOSED (sağlıklı), 1 = OPEN (rollback!), 2 = HALF_OPEN (recovery)
// - authzRequired modda ZORUNLU, diğerinde optional
const cbState = metrics.openfga_circuit_breaker_state;
if (typeof cbState !== 'number') {
  if (authzRequired) {
    violations.push('[zanzibar-canary] openfga_circuit_breaker_state metric eksik — CB durumu tespit edilemiyor');
  }
} else if (cbState > thresholds.openFgaCbMax) {
  const label = cbState === 1 ? 'OPEN' : cbState === 2 ? 'HALF_OPEN' : `UNKNOWN(${cbState})`;
  violations.push(`OpenFGA circuit breaker state=${cbState} (${label}) > eşik ${thresholds.openFgaCbMax} (CLOSED). Rollback değerlendir.`);
}

// NO_SIGNAL tespit: canary mode'da authz_decisions_total minimum
// threshold'un altında ise "synthetic yük yeterli değil" violation.
if (authzRequired) {
  const decisions = metrics.authz_decisions_total;
  if (typeof decisions !== 'number') {
    violations.push(
      '[zanzibar-canary] authz_decisions_total metric eksik — NO_SIGNAL tespiti yapılamıyor',
    );
  } else if (decisions < thresholds.authzMinDecisions) {
    violations.push(
      `[zanzibar-canary] NO_SIGNAL: authz_decisions_total=${decisions} < min ${thresholds.authzMinDecisions} (synthetic yük yetersiz; k6 persona matrix daha fazla çağrı üretmeli)`,
    );
  }
}

const summary = [
  `Canary weight: ${weight}%`,
  phase ? `Phase: ${phase}` : '',
  `TTFB p95: ${metrics.ttfb_p95_ms}ms (eşik ${thresholds.ttfb}ms)`,
  `Error rate: ${metrics.error_rate_pct}% (eşik ${thresholds.errorRate}%)`,
  `Sentry error: ${metrics.sentry_error_rate_pct}% (eşik ${thresholds.sentry}%)`,
  `Audit filter kullanımı: ${metrics.audit_filter_usage_pct}% (min ${thresholds.auditFilterUsage}%)`,
  typeof metrics.authz_check_p95_ms === 'number' ? `AuthZ p95: ${metrics.authz_check_p95_ms}ms (eşik ${thresholds.authzCheckP95}ms)` : '',
  typeof metrics.authz_deny_rate_pct === 'number' ? `AuthZ deny: ${metrics.authz_deny_rate_pct}% (eşik ${thresholds.authzDenyRate}%)` : '',
  typeof metrics.authz_error_rate_pct === 'number' ? `AuthZ error: ${metrics.authz_error_rate_pct}% (eşik ${thresholds.authzErrorRate}%)` : '',
  typeof metrics.authz_cache_miss_rate_pct === 'number'
    ? `AuthZ cache miss: ${metrics.authz_cache_miss_rate_pct}%${phaseIsCold ? ' (cold: soften)' : phaseIsWarm ? ` (warm eşik ${thresholds.authzCacheMiss}%)` : ` (eşik ${thresholds.authzCacheMiss}%)`}`
    : '',
  typeof metrics.openfga_circuit_breaker_state === 'number' ? `OpenFGA CB: ${metrics.openfga_circuit_breaker_state} (0=CLOSED)` : '',
].filter(Boolean).join(' | ');

if (violations.length > 0) {
  console.error('❌ Guardrail ihlali tespit edildi.');
  console.error(summary);
  violations.forEach((line) => console.error(`  - ${line}`));
  process.exitCode = 1;
} else {
  console.log('✅ Guardrail metrikleri eşik altında, canary adımı geçerli.');
  console.log(summary);
}
