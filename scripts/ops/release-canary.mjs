#!/usr/bin/env node
import { mkdirSync, writeFileSync, existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import process from 'node:process';

const __dirname = path.dirname(new URL(import.meta.url).pathname);
const repoRoot = path.resolve(__dirname, '..', '..', '..');
const backendRoot = path.join(repoRoot, 'backend');
const now = new Date();
const stamp = now.toISOString().replace(/[:.]/g, '-');

const args = process.argv.slice(2);
const getArg = (name, fallback = null) => {
  const idx = args.indexOf(name);
  if (idx === -1) return fallback;
  return args[idx + 1] ?? fallback;
};

const mode = getArg('--mode', process.env.CANARY_MODE || 'sample');
const envName = getArg('--env', process.env.CANARY_ENV || 'stage');
const weights = (getArg('--weights', process.env.CANARY_WEIGHTS || '10,50,100') || '10,50,100')
  .split(',')
  .map((item) => Number.parseInt(item.trim(), 10))
  .filter((item) => Number.isFinite(item));
const outDir = path.resolve(
  repoRoot,
  getArg('--out-dir', path.join('backend', 'test-results', 'canary', stamp)),
);
const logsDir = path.join(outDir, 'logs');
mkdirSync(logsDir, { recursive: true });

const runNode = (scriptPath, scriptArgs, extraEnv = {}) => {
  const result = spawnSync(process.execPath, [scriptPath, ...scriptArgs], {
    cwd: repoRoot,
    env: { ...process.env, ...extraEnv },
    encoding: 'utf-8',
  });
  return {
    status: result.status ?? 1,
    stdout: result.stdout ?? '',
    stderr: result.stderr ?? '',
  };
};

const fetchCheck = async (url) => {
  const startedAt = new Date().toISOString();
  try {
    const response = await fetch(url, { method: 'GET' });
    return {
      url,
      ok: response.ok,
      status: response.status,
      startedAt,
      endedAt: new Date().toISOString(),
    };
  } catch (error) {
    return {
      url,
      ok: false,
      status: null,
      startedAt,
      endedAt: new Date().toISOString(),
      error: error instanceof Error ? error.message : String(error),
    };
  }
};

const postHook = async (url, payload) => {
  if (!url) {
    return { skipped: true, reason: 'missing-hook-url' };
  }
  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(payload),
    });
    return {
      skipped: false,
      ok: response.ok,
      status: response.status,
      body: await response.text(),
    };
  } catch (error) {
    return {
      skipped: false,
      ok: false,
      status: null,
      error: error instanceof Error ? error.message : String(error),
    };
  }
};

const writeFile = (filePath, value) => {
  mkdirSync(path.dirname(filePath), { recursive: true });
  writeFileSync(filePath, value, 'utf-8');
};

const requireEnv = (name) => {
  const value = (process.env[name] || '').trim();
  if (!value) {
    throw new Error(`Live canary icin eksik environment: ${name}`);
  }
  return value;
};

const requireOneOf = (names) => {
  for (const name of names) {
    const value = (process.env[name] || '').trim();
    if (value) {
      return { name, value };
    }
  }
  throw new Error(`Live canary icin su alanlardan en az biri zorunlu: ${names.join(', ')}`);
};

const validateLiveProvisioning = () => {
  if (mode !== 'live') {
    return;
  }

  requireOneOf([
    'CANARY_APPLY_WEIGHT_HOOK_URL',
    'CANARY_WEIGHT_10_HOOK_URL',
    'CANARY_WEIGHT_50_HOOK_URL',
    'CANARY_WEIGHT_100_HOOK_URL',
  ]);
  requireEnv('CANARY_ROLLBACK_HOOK_URL');
  requireEnv('CANARY_WEB_SMOKE_URL');
  const backendHealthUrls = (process.env.CANARY_BACKEND_HEALTH_URLS || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
  if (backendHealthUrls.length === 0) {
    throw new Error('Live canary icin CANARY_BACKEND_HEALTH_URLS zorunludur.');
  }
  requireEnv('CANARY_PROM_URL');
  requireOneOf(['GRAFANA_API_KEY', 'CANARY_API_KEY']);
  [
    'CANARY_TTFB_QUERY',
    'CANARY_ERROR_QUERY',
    'CANARY_SENTRY_QUERY',
    'CANARY_AUDIT_FILTER_QUERY',
    'CANARY_AUDIT_TOTAL_QUERY',
  ].forEach(requireEnv);
};

const resolveMetrics = (weight) => {
  const metricsFile = path.join(outDir, `metrics-${weight}.json`);
  if (mode === 'sample') {
    const sampleFile = path.join(backendRoot, 'scripts', 'ci', 'canary', 'samples', `stage${weight}.json`);
    if (!existsSync(sampleFile)) {
      throw new Error(`Sample metrics dosyasi bulunamadi: ${sampleFile}`);
    }
    const raw = JSON.parse(readFileSync(sampleFile, 'utf-8'));
    writeFile(metricsFile, JSON.stringify(raw, null, 2));
    return { metricsFile, source: sampleFile, collected: { mode: 'sample', sampleFile } };
  }

  const collector = path.join(backendRoot, 'scripts', 'ci', 'canary', 'pull-grafana-metrics.mjs');
  const result = runNode(collector, ['--output', metricsFile], {
    CANARY_ENV: envName,
  });
  writeFile(path.join(logsDir, `pull-metrics-${weight}.stdout.log`), result.stdout);
  writeFile(path.join(logsDir, `pull-metrics-${weight}.stderr.log`), result.stderr);
  if (result.status !== 0) {
    throw new Error(`Canary metrics toplama basarisiz (weight=${weight})`);
  }
  return { metricsFile, source: collector, collected: { mode: 'live' } };
};

const validateMetrics = (metricsFile, weight) => {
  const checker = path.join(backendRoot, 'scripts', 'ci', 'canary', 'guardrail-check.mjs');
  const result = runNode(checker, ['--metrics', metricsFile, '--weight', String(weight)], {
    CANARY_ENV: envName,
  });
  writeFile(path.join(logsDir, `guardrail-${weight}.stdout.log`), result.stdout);
  writeFile(path.join(logsDir, `guardrail-${weight}.stderr.log`), result.stderr);
  return result;
};

const validateRuntime = async () => {
  const webSmokeUrl = process.env.CANARY_WEB_SMOKE_URL || process.env.WEB_SMOKE_URL || '';
  const backendUrls = (process.env.CANARY_BACKEND_HEALTH_URLS || process.env.BACKEND_HEALTH_URLS || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);

  const web = webSmokeUrl ? await fetchCheck(webSmokeUrl) : { skipped: true, reason: 'missing-web-smoke-url' };
  const backend = [];
  for (const url of backendUrls) {
    backend.push(await fetchCheck(url));
  }
  return { web, backend };
};

const main = async () => {
  validateLiveProvisioning();
  const stageResults = [];
  let overallStatus = 'PASS';
  let rollbackResult = { skipped: true, reason: 'not-triggered' };

  for (const weight of weights) {
    const hookUrl = process.env[`CANARY_WEIGHT_${weight}_HOOK_URL`] || process.env.CANARY_APPLY_WEIGHT_HOOK_URL || '';
    const hookPayload = { env: envName, weight, mode };
    const advance = await postHook(hookUrl, hookPayload);

    if (mode === 'live' && !hookUrl) {
      throw new Error(`Live canary icin hook eksik: CANARY_WEIGHT_${weight}_HOOK_URL veya CANARY_APPLY_WEIGHT_HOOK_URL`);
    }

    const { metricsFile, source, collected } = resolveMetrics(weight);
    const guardrail = validateMetrics(metricsFile, weight);
    const runtime = await validateRuntime();

    const runtimeFail = !runtime.web.skipped && !runtime.web.ok
      || runtime.backend.some((item) => item.ok === false);
    const stageStatus = guardrail.status === 0 && !runtimeFail && (advance.skipped || advance.ok) ? 'PASS' : 'FAIL';
    if (stageStatus !== 'PASS') {
      overallStatus = 'FAIL';
    }

    stageResults.push({
      weight,
      status: stageStatus,
      hook: advance,
      metrics_file: path.relative(repoRoot, metricsFile),
      metrics_source: path.relative(repoRoot, source),
      metrics_collection: collected,
      runtime,
      guardrail: {
        status: guardrail.status,
        stdout_log: path.relative(repoRoot, path.join(logsDir, `guardrail-${weight}.stdout.log`)),
        stderr_log: path.relative(repoRoot, path.join(logsDir, `guardrail-${weight}.stderr.log`)),
      },
    });

    if (stageStatus !== 'PASS') {
      rollbackResult = await postHook(process.env.CANARY_ROLLBACK_HOOK_URL || '', {
        env: envName,
        weight,
        mode,
        reason: 'guardrail-or-runtime-failure',
      });
      break;
    }
  }

  const summary = {
    generated_at: now.toISOString(),
    env: envName,
    mode,
    weights,
    overall_status: overallStatus,
    rollback: rollbackResult,
    stages: stageResults,
  };

  const summaryJson = path.join(outDir, 'canary.summary.v1.json');
  const summaryMd = path.join(outDir, 'canary.summary.v1.md');
  writeFile(summaryJson, JSON.stringify(summary, null, 2));

  const md = [
    '# Canary Summary v1',
    '',
    `- env: ${envName}`,
    `- mode: ${mode}`,
    `- overall_status: ${overallStatus}`,
    '',
    '## Stages',
    ...stageResults.flatMap((stage) => [
      `- weight=${stage.weight} status=${stage.status}`,
      `  - metrics: ${stage.metrics_file}`,
      `  - guardrail_status: ${stage.guardrail.status}`,
      `  - web_smoke: ${stage.runtime.web.skipped ? 'skipped' : stage.runtime.web.status}`,
      `  - backend_checks: ${stage.runtime.backend.length}`,
    ]),
    '',
    `## Rollback`,
    `- skipped: ${rollbackResult.skipped ? 'true' : 'false'}`,
    rollbackResult.reason ? `- reason: ${rollbackResult.reason}` : `- status: ${rollbackResult.status ?? 'n/a'}`,
  ].join('\n');
  writeFile(summaryMd, md);

  console.log(JSON.stringify({
    status: overallStatus,
    out_json: path.relative(repoRoot, summaryJson),
    out_md: path.relative(repoRoot, summaryMd),
  }, null, 2));

  process.exit(overallStatus === 'PASS' ? 0 : 1);
};

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
});
