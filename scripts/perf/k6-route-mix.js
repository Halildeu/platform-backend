import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN || '';
const DURATION = __ENV.DURATION || '2m';

export const options = {
  scenarios: {
    users_list: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.USERS_RPS || '60'),
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 60,
      exec: 'listUsers',
    },
    variants_list: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.VARIANTS_RPS || '30'),
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 30,
      exec: 'listVariants',
      startTime: '0s',
    },
    users_export: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.EXPORT_RPS || '10'),
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 10,
      exec: 'exportUsers',
      startTime: '0s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<400'],
  },
};

function headers() {
  const h = { 'Content-Type': 'application/json' };
  if (TOKEN) h['Authorization'] = `Bearer ${TOKEN}`;
  return h;
}

export function listUsers() {
  const res = http.get(`${BASE}/api/users/all?page=1&pageSize=50&sort=createDate,desc`, { headers: headers() });
  check(res, { '200 list': (r) => r.status === 200 });
  sleep(0.05);
}

export function listVariants() {
  const res = http.get(`${BASE}/api/variants?gridId=test`, { headers: headers() });
  check(res, { '200 variants': (r) => r.status === 200 || r.status === 404 });
  sleep(0.05);
}

export function exportUsers() {
  const res = http.get(`${BASE}/api/users/export.csv`, { headers: headers() });
  check(res, { '200 export': (r) => r.status === 200 });
  sleep(0.05);
}

