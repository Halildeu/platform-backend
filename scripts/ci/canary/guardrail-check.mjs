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
};

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

// Zanzibar authz guardrails (optional — only checked if metrics present)
if (typeof metrics.authz_check_p95_ms === 'number' && metrics.authz_check_p95_ms > thresholds.authzCheckP95) {
  violations.push(
    `AuthZ check p95 ${metrics.authz_check_p95_ms}ms > eşik ${thresholds.authzCheckP95}ms`,
  );
}
if (typeof metrics.authz_deny_rate_pct === 'number' && metrics.authz_deny_rate_pct > thresholds.authzDenyRate) {
  violations.push(
    `AuthZ deny oranı ${metrics.authz_deny_rate_pct}% > eşik ${thresholds.authzDenyRate}%`,
  );
}

const summary = [
  `Canary weight: ${weight}%`,
  `TTFB p95: ${metrics.ttfb_p95_ms}ms (eşik ${thresholds.ttfb}ms)`,
  `Error rate: ${metrics.error_rate_pct}% (eşik ${thresholds.errorRate}%)`,
  `Sentry error: ${metrics.sentry_error_rate_pct}% (eşik ${thresholds.sentry}%)`,
  `Audit filter kullanımı: ${metrics.audit_filter_usage_pct}% (min ${thresholds.auditFilterUsage}%)`,
  typeof metrics.authz_check_p95_ms === 'number' ? `AuthZ p95: ${metrics.authz_check_p95_ms}ms (eşik ${thresholds.authzCheckP95}ms)` : '',
  typeof metrics.authz_deny_rate_pct === 'number' ? `AuthZ deny: ${metrics.authz_deny_rate_pct}% (eşik ${thresholds.authzDenyRate}%)` : '',
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
