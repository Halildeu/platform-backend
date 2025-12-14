#!/usr/bin/env node
/* eslint-disable no-console */
// Canary guardrail placeholder script.
// Amaç: Grafana/Sentry API’lerinden metrik çekip eşik kontrolü yapmak.

import process from 'node:process';

const env = process.argv.includes('--env')
  ? process.argv[process.argv.indexOf('--env') + 1]
  : 'stage';

console.log(`[guardrails] Env: ${env}`);
console.log('[guardrails] Placeholder: burada Grafana/Sentry API çağrısı yapın ve eşikleri kontrol edin.');
console.log('[guardrails] Örnek eşikler: TTFA < 3000ms, error rate < 2%, Sentry issue spike yok.');

// TODO: HTTP çağrıları ekleyip eşik ihlalinde process.exit(1) yap.
process.exit(0);
