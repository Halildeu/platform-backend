import http from 'k6/http';
import { check, sleep } from 'k6';

// Env
const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN || '';
const RPS = Number(__ENV.RPS || '50');
const DURATION = __ENV.DURATION || '1m';
const SEARCH = __ENV.SEARCH || '';

export const options = {
  scenarios: {
    users_list: {
      executor: 'constant-arrival-rate',
      rate: RPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(10, RPS),
      exec: 'listUsers',
    },
    users_export: {
      executor: 'constant-arrival-rate',
      rate: Math.ceil(RPS / 5),
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(10, Math.ceil(RPS / 5)),
      startTime: '0s',
      exec: 'exportUsers',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<300'],
  },
};

function headers() {
  const h = { 'Content-Type': 'application/json' };
  if (TOKEN) h['Authorization'] = `Bearer ${TOKEN}`;
  return h;
}

export function listUsers() {
  let qs = 'page=1&pageSize=50&sort=createDate,desc';
  if (SEARCH) qs += `&search=${encodeURIComponent(SEARCH)}`;
  const res = http.get(`${BASE}/api/users/all?${qs}` , { headers: headers() });
  check(res, { '200': (r) => r.status === 200 });
  sleep(0.1);
}

export function exportUsers() {
  const res = http.get(`${BASE}/api/users/export.csv`, { headers: headers() });
  check(res, { '200': (r) => r.status === 200 });
  sleep(0.1);
}
