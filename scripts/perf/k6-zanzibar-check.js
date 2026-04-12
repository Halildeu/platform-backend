import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

/**
 * k6 performance test for Zanzibar authorization endpoints.
 * Validates SK-2 (p95 <15ms) and SK-11 (batch p95 <50ms).
 *
 * Usage:
 *   k6 run --env BASE_URL=http://localhost:8092 backend/scripts/perf/k6-zanzibar-check.js
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8092';
const TOKEN = __ENV.AUTH_TOKEN || __ENV.TOKEN || '';

const checkLatency = new Trend('authz_check_latency', true);
const batchLatency = new Trend('authz_batch_latency', true);
const errorRate = new Rate('authz_error_rate');

export const options = {
  stages: [
    { duration: '10s', target: 5 },
    { duration: '30s', target: 10 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    'authz_check_latency': ['p(95)<15'],   // SK-2: p95 <15ms
    'authz_batch_latency': ['p(95)<50'],   // SK-11: batch p95 <50ms
    'authz_error_rate': ['rate<0.001'],     // SK-1: <0.1% error
  },
};

const headers = {
  'Content-Type': 'application/json',
  ...(TOKEN ? { 'Authorization': `Bearer ${TOKEN}` } : {}),
};

export default function () {
  // Single check
  const checkRes = http.post(
    `${BASE_URL}/api/v1/authz/check`,
    JSON.stringify({ relation: 'can_view', objectType: 'report', objectId: 'HR_REPORTS' }),
    { headers }
  );
  checkLatency.add(checkRes.timings.duration);
  errorRate.add(checkRes.status !== 200);
  check(checkRes, { 'check 200': (r) => r.status === 200 });

  // Batch check
  const batchRes = http.post(
    `${BASE_URL}/api/v1/authz/batch-check`,
    JSON.stringify({
      checks: [
        { relation: 'can_view', objectType: 'report', objectId: 'HR_REPORTS' },
        { relation: 'can_view', objectType: 'module', objectId: 'AUDIT' },
        { relation: 'can_view', objectType: 'module', objectId: 'THEME' },
      ],
    }),
    { headers }
  );
  batchLatency.add(batchRes.timings.duration);
  errorRate.add(batchRes.status !== 200);
  check(batchRes, { 'batch 200': (r) => r.status === 200 });

  sleep(0.5);
}
