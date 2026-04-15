import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

/**
 * k6 Zanzibar synthetic canary — 5 persona matrix.
 *
 * CNS-20260415-004 (Codex) tasarımı: Stage 2 "synthetic canary" için fonksiyonel
 * persona doğrulaması ile operasyonel guardrail'i ayrı iki sinyal katmanına böl.
 *
 * 5 persona: super-admin / read-only / restricted / multi-role+DENY / scope-less
 * 2 faz:     cold (cache soğuk) + warm (cache ısınmış) — ayrı iki run, PHASE env ile
 *
 * Modlar:
 *   - ESTIMATE_ONLY=1  → HTTP atma, sadece request mix + decision floor JSON yaz, exit 0
 *   - PHASE=cold|warm  → tüm metric'lere tag olarak basılır; artifact ayrılır
 *   - PERSONA_TOKENS=path  → setup script'in ürettiği token haritası; yoksa
 *     AUTH_TOKEN env'i tüm persona'lar için fallback olarak kullanılır (local permitAll)
 *
 * Kullanım:
 *   # Estimate (HTTP yok, calibration için):
 *   ESTIMATE_ONLY=1 k6 run backend/scripts/perf/k6-zanzibar-check.js
 *
 *   # Cold phase (staging, persona token'ları setup script tarafından üretildi):
 *   PHASE=cold PERSONA_TOKENS=.cache/zanzibar-canary/persona-tokens.json \
 *     k6 run backend/scripts/perf/k6-zanzibar-check.js
 *
 *   # Warm phase (cold hemen sonrası, version bump yok):
 *   PHASE=warm PERSONA_TOKENS=... k6 run ...
 *
 *   # Local smoke (permitAll, token'sız):
 *   BASE_URL=http://localhost:8090 k6 run ...
 */

// ─────────────────────────────────────────────────────────────────────────────
// Config / ENV
// ─────────────────────────────────────────────────────────────────────────────

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090'; // permission-service hub
const PHASE = (__ENV.PHASE || 'cold').toLowerCase(); // 'cold' | 'warm'
const ESTIMATE_ONLY = __ENV.ESTIMATE_ONLY === '1' || __ENV.ESTIMATE_ONLY === 'true';
const DURATION = __ENV.DURATION || '30m'; // CNS-004 önerilen 30dk MVP
const FALLBACK_TOKEN = __ENV.AUTH_TOKEN || __ENV.TOKEN || '';

/**
 * CNS-20260416-001 B2 fix — local permitAll mode:
 * OpenFGA disabled olduğunda (SecurityConfigLocal permitAll) tüm /authz/check
 * ALLOW döner; intentional DENY'ler mismatch threshold'u patlatır.
 *
 * Çözüm: LOCAL_PERMIT_ALL=1 env'i → doCheck içinde "expected=deny" olan çağrılarda
 * expected override edilir "allow" olarak. Staging auth-enabled ortamda bu env
 * basılmaz ve gerçek intent uygulanır. `authz_persona_mismatch: rate<0.01`
 * threshold'u her iki modda da korunur.
 *
 * Bu, test environment flag pattern'i (CI=true, NODE_ENV=test gibi) —
 * Zanzibar/OpenFGA disabled davranışı dokümante edilmiş bir semantic deviation.
 */
const LOCAL_PERMIT_ALL = __ENV.LOCAL_PERMIT_ALL === '1' || __ENV.LOCAL_PERMIT_ALL === 'true';

// Persona token haritası — JSON file path veya inline JSON string
// Format: {"super_admin":"eyJ...", "read_only":"eyJ...", ...}
const PERSONA_TOKENS = (() => {
  const raw = __ENV.PERSONA_TOKENS_JSON || '';
  if (raw) {
    try {
      return JSON.parse(raw);
    } catch (e) {
      console.warn(`PERSONA_TOKENS_JSON parse hatası, fallback kullanılıyor: ${e.message}`);
    }
  }
  return {}; // boş → fallback token kullanılır
})();

// ─────────────────────────────────────────────────────────────────────────────
// Custom Metrics
// ─────────────────────────────────────────────────────────────────────────────
//
// CNS-004 iki sinyal ayrımı:
//   - authz_persona_outcome (Counter) → persona/phase/expected/actual/reason tag'leri
//     ile fonksiyonel doğrulama. Guardrail AGG'ye değil, persona-intent'e bakar.
//   - authz_persona_latency (Trend) → persona/phase bazlı latency (SK-2 kontrolü)
//   - authz_persona_mismatch (Rate) → expected ≠ actual → gerçek regresyon sinyali
//
// Servis tarafındaki authz_decisions_total counter (OpenFgaAuthzService) ZATEN
// her check'i kaydeder; bu persona metric'leri ek katman, replace değil.
//
// ─────────────────────────────────────────────────────────────────────────────

const personaOutcome = new Counter('authz_persona_outcome');
const personaLatency = new Trend('authz_persona_latency', true);
const personaMismatch = new Rate('authz_persona_mismatch');
const httpErrors = new Rate('authz_http_error');

// ─────────────────────────────────────────────────────────────────────────────
// Scenario Yapılandırma — CNS-004 Codex yük profili tablosu
// ─────────────────────────────────────────────────────────────────────────────
//
// Codex tablosu (sequence/min):
//   super-admin       10 → ~60 check/min
//   read-only          8 → ~42 check/min (intentional write-deny ~2/min)
//   restricted         6 → ~26 check/min (intentional deny ~2/min)
//   multi-role+DENY    5 → ~22 check/min (intentional blocked deny ~1.7/min)
//   scope-less         4 → ~13 check/min (intentional data-deny ~1/min)
//   TOPLAM           ~160 decisions/min × 30dk = 4800+ → authz_decisions_total >= 1000 ✓
//
// Intentional deny band: %4-5 (aggregate guardrail kirlenmesin).
//
// ─────────────────────────────────────────────────────────────────────────────

/**
 * CNS-20260416-001 M2 fix: VU allocation artışı.
 * Cold phase'de JVM/OpenFGA/Keycloak ilk vuruşları birkaç saniyeye çıkabilir →
 * `dropped_iterations` riski. Codex önerisi:
 *   - Yüksek rate personalar (super/read/restricted): 4/10
 *   - Düşük rate personalar (multi-role/scope-less): 3/8
 */
const SCENARIOS = {
  super_admin: {
    executor: 'constant-arrival-rate',
    rate: 10,
    timeUnit: '1m',
    duration: DURATION,
    preAllocatedVUs: 4,
    maxVUs: 10,
    exec: 'personaSuperAdmin',
    tags: { persona: 'super_admin', phase: PHASE },
  },
  read_only: {
    executor: 'constant-arrival-rate',
    rate: 8,
    timeUnit: '1m',
    duration: DURATION,
    preAllocatedVUs: 4,
    maxVUs: 10,
    exec: 'personaReadOnly',
    tags: { persona: 'read_only', phase: PHASE },
  },
  restricted: {
    executor: 'constant-arrival-rate',
    rate: 6,
    timeUnit: '1m',
    duration: DURATION,
    preAllocatedVUs: 4,
    maxVUs: 10,
    exec: 'personaRestricted',
    tags: { persona: 'restricted', phase: PHASE },
  },
  multi_role_deny: {
    executor: 'constant-arrival-rate',
    rate: 5,
    timeUnit: '1m',
    duration: DURATION,
    preAllocatedVUs: 3,
    maxVUs: 8,
    exec: 'personaMultiRoleDeny',
    tags: { persona: 'multi_role_deny', phase: PHASE },
  },
  scope_less: {
    executor: 'constant-arrival-rate',
    rate: 4,
    timeUnit: '1m',
    duration: DURATION,
    preAllocatedVUs: 3,
    maxVUs: 8,
    exec: 'personaScopeLess',
    tags: { persona: 'scope_less', phase: PHASE },
  },
};

export const options = ESTIMATE_ONLY
  ? {
      // Estimate mode: hiç VU çalıştırma, sadece setup/teardown'dan bilgi üret
      scenarios: {
        estimate: {
          executor: 'per-vu-iterations',
          vus: 1,
          iterations: 1,
          exec: 'estimateOnly',
        },
      },
    }
  : {
      scenarios: SCENARIOS,
      thresholds: {
        // Persona intent regresyonu — expected ≠ actual %1'in üstüne çıkmasın
        'authz_persona_mismatch': ['rate<0.01'],
        // HTTP hata oranı — %0.5 eşiği (guardrail ile uyumlu)
        'authz_http_error': ['rate<0.005'],
        // Latency — SK-2 aggregate (persona tag'siz)
        'authz_persona_latency': ['p(95)<50'],
      },
    };

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

function tokenFor(persona) {
  return PERSONA_TOKENS[persona] || FALLBACK_TOKEN;
}

function headers(persona) {
  const t = tokenFor(persona);
  const h = { 'Content-Type': 'application/json' };
  if (t) h['Authorization'] = `Bearer ${t}`;
  return h;
}

/**
 * Tek object-level authz check.
 * CNS-004 tasarımı: expected ∈ {allow, deny} — gelen sonuç (actual) ile karşılaştırılır.
 * `authz_persona_outcome` counter'ına tag'li olarak yazılır; mismatch rate güncellenir.
 */
function doCheck(persona, rel, objType, objId, expectedRaw, tagReason = '') {
  // B2 fix: Local permitAll'da tüm check'ler ALLOW döner → intent'i runtime'da override et
  const expected = LOCAL_PERMIT_ALL && expectedRaw === 'deny' ? 'allow' : expectedRaw;

  const body = JSON.stringify({ relation: rel, objectType: objType, objectId: objId });
  const res = http.post(`${BASE_URL}/api/v1/authz/check`, body, {
    headers: headers(persona),
    tags: { persona, phase: PHASE, expected },
  });

  personaLatency.add(res.timings.duration, { persona, phase: PHASE });

  const is2xx = res.status >= 200 && res.status < 300;
  httpErrors.add(!is2xx, { persona, phase: PHASE });

  if (!is2xx) {
    personaOutcome.add(1, { persona, phase: PHASE, expected, actual: 'http_error', reason: `http_${res.status}` });
    personaMismatch.add(true, { persona, phase: PHASE });
    return;
  }

  let payload;
  try {
    payload = res.json();
  } catch (_) {
    personaOutcome.add(1, { persona, phase: PHASE, expected, actual: 'parse_error', reason: 'invalid_json' });
    personaMismatch.add(true, { persona, phase: PHASE });
    return;
  }

  const actual = payload && payload.allowed ? 'allow' : 'deny';
  const reason = (payload && payload.reason) || tagReason || 'unknown';
  personaOutcome.add(1, { persona, phase: PHASE, expected, actual, reason });
  personaMismatch.add(expected !== actual, { persona, phase: PHASE });
}

/**
 * /authz/me — kullanıcı snapshot'ı. Persona token'ının permission-service hub'a
 * ulaştığını doğrular. Outcome: HTTP 200 → allow, diğer durum → deny/http_error.
 */
function doAuthzMe(persona) {
  const res = http.get(`${BASE_URL}/api/v1/authz/me`, {
    headers: headers(persona),
    tags: { persona, phase: PHASE, endpoint: 'me' },
  });
  personaLatency.add(res.timings.duration, { persona, phase: PHASE });
  const is2xx = res.status >= 200 && res.status < 300;
  httpErrors.add(!is2xx, { persona, phase: PHASE });
  check(res, { 'authz/me 2xx': () => is2xx });
}

// ─────────────────────────────────────────────────────────────────────────────
// Persona Functions — Codex CNS-004 yük profili tablosu
// ─────────────────────────────────────────────────────────────────────────────

/**
 * super-admin — 10 seq/min, 6 check/seq = 60 decisions/min, 0 intentional deny.
 * Tüm feature/data check allowed bekleniyor. Deny olursa regresyon.
 */
export function personaSuperAdmin() {
  const p = 'super_admin';
  doAuthzMe(p);
  doCheck(p, 'can_manage', 'module', 'ACCESS', 'allow');
  doCheck(p, 'can_view',   'module', 'THEME',  'allow');
  doCheck(p, 'can_view',   'module', 'REPORT', 'allow');
  doCheck(p, 'viewer',     'company', '1',     'allow');
  doCheck(p, 'admin',      'organization', 'default', 'allow');
  sleep(0.1);
}

/**
 * read-only — 8 seq/min, ~42 check/min. 5 allow + 1 write-deny/4seq (~2/min).
 * VIEW izinleri var; MANAGE yok. Scope: company:1.
 */
export function personaReadOnly() {
  const p = 'read_only';
  doAuthzMe(p);
  doCheck(p, 'can_view', 'module', 'ACCESS', 'allow');
  doCheck(p, 'can_view', 'module', 'REPORT', 'allow');
  doCheck(p, 'can_view', 'module', 'COMPANY', 'allow');
  doCheck(p, 'viewer',   'company', '1',      'allow');
  // Intentional write-deny — her 4 seq'te 1 (oran %20 iç seq'te → toplam personanın intent'i %4 band)
  if (__ITER % 4 === 0) {
    doCheck(p, 'can_manage', 'module', 'ACCESS', 'deny', 'no_manage_grant');
  }
  sleep(0.1);
}

/**
 * restricted — 6 seq/min, ~26 check/min. 4 allow + 1 deny/3seq.
 * ACCESS/REPORT/COMPANY: VIEW. THEME/AUDIT: yok. Scope: company:1, company:2 yok.
 */
export function personaRestricted() {
  const p = 'restricted';
  doAuthzMe(p);
  doCheck(p, 'can_view', 'module', 'ACCESS', 'allow');
  doCheck(p, 'can_view', 'module', 'REPORT', 'allow');
  doCheck(p, 'can_view', 'module', 'COMPANY', 'allow');
  doCheck(p, 'viewer',   'company', '1',      'allow');
  // Intentional deny path — her 3 seq'te 1
  if (__ITER % 3 === 0) {
    doCheck(p, 'can_view', 'module', 'THEME',  'deny', 'no_grant');
    doCheck(p, 'viewer',   'company', '2',     'deny', 'no_scope');
  }
  sleep(0.1);
}

/**
 * multi-role+DENY — 5 seq/min, ~22 check/min. Deny-wins regresyon testi.
 * CANARY_PURCHASE_MANAGER (PURCHASE MANAGE + action:CREATE_PO ALLOW) +
 * CANARY_DENY_DELETE (action:DELETE_PO DENY). İkisi aynı user'a atanır.
 */
export function personaMultiRoleDeny() {
  const p = 'multi_role_deny';
  doAuthzMe(p);
  doCheck(p, 'can_manage', 'module', 'PURCHASE',       'allow');
  doCheck(p, 'can_view',   'module', 'REPORT',         'allow');
  doCheck(p, 'allowed',    'action', 'CREATE_PO',      'allow');
  // DENY always wins — her 3 seq'te 1 explicit
  if (__ITER % 3 === 0) {
    doCheck(p, 'allowed', 'action', 'DELETE_PO', 'deny', 'blocked');
  }
  sleep(0.1);
}

/**
 * scope-less — 4 seq/min, ~13 check/min. Feature izni var, data scope yok.
 * COMPANY/REPORT VIEW; hiç company/project/warehouse scope yok.
 */
export function personaScopeLess() {
  const p = 'scope_less';
  doAuthzMe(p);
  doCheck(p, 'can_view', 'module', 'COMPANY', 'allow');
  doCheck(p, 'can_view', 'module', 'REPORT',  'allow');
  // Data scope deny path — her 4 seq'te 1
  if (__ITER % 4 === 0) {
    doCheck(p, 'viewer', 'company', '1', 'deny', 'no_scope');
  }
  sleep(0.1);
}

// ─────────────────────────────────────────────────────────────────────────────
// Estimate-Only Mode — CNS-004 calibration
// ─────────────────────────────────────────────────────────────────────────────

// Estimate projeksiyon modülü — handleSummary'den de erişilebilsin
function computeEstimateSummary() {
  const profile = [
    { persona: 'super_admin',    seqPerMin: 10, checksPerSeq: 6, intentionalDenyPerMin: 0 },
    { persona: 'read_only',      seqPerMin:  8, checksPerSeq: 5, intentionalDenyPerMin: 2 },
    { persona: 'restricted',     seqPerMin:  6, checksPerSeq: 4, intentionalDenyPerMin: 2 },
    { persona: 'multi_role_deny',seqPerMin:  5, checksPerSeq: 4, intentionalDenyPerMin: 1.7 },
    { persona: 'scope_less',     seqPerMin:  4, checksPerSeq: 3, intentionalDenyPerMin: 1 },
  ];

  const durationMinutes = 30; // CNS-004 MVP window
  let totalDecisions = 0;
  let totalIntentionalDeny = 0;

  for (const p of profile) {
    const decisionsPerMin = p.seqPerMin * p.checksPerSeq;
    totalDecisions += decisionsPerMin * durationMinutes;
    totalIntentionalDeny += p.intentionalDenyPerMin * durationMinutes;
  }

  const intentionalDenyPct = (totalIntentionalDeny / totalDecisions) * 100;

  return {
    mode: 'ESTIMATE_ONLY',
    ref: 'CNS-20260415-004',
    phase: PHASE,
    localPermitAll: LOCAL_PERMIT_ALL,
    durationMinutes,
    profile,
    totalDecisionsProjected: totalDecisions,
    totalIntentionalDenyProjected: totalIntentionalDeny,
    intentionalDenyPct: Number(intentionalDenyPct.toFixed(2)),
    guardrailMinDecisions: 1000,
    noSignalRisk: totalDecisions < 1000 ? 'HIGH' : 'LOW',
    notes: [
      'authz_decisions_total hesabı single /check floor üzerinden; batch katkısı eklenirse üst sınır artar.',
      '/authz/me ve endpoint kontrolleri eklenmedi — toplam rakam daha yüksek olur.',
      'intentional deny aggregate %4-5 bandında kalmalı; staging deny_rate guardrail\'i kirlenmesin.',
      LOCAL_PERMIT_ALL ? 'LOCAL_PERMIT_ALL=1 aktif: intentional deny\'ler expected=allow\'a override edilir.' : '',
    ].filter(Boolean),
  };
}

export function estimateOnly() {
  const summary = computeEstimateSummary();
  console.log('────────── ESTIMATE_ONLY ──────────');
  console.log(JSON.stringify(summary, null, 2));
  console.log('──────────────────────────────────');
}

// ─────────────────────────────────────────────────────────────────────────────
// Summary Output — k6 default JSON'u genişlet
// ─────────────────────────────────────────────────────────────────────────────

export function handleSummary(data) {
  /**
   * CNS-20260416-001 Q5 fix: Summary output path'i `SUMMARY_PATH` env ile
   * dinamik olarak kontrol edilir. Wrapper cold/warm fazları için farklı
   * path set eder → artifact çakışması olmaz. k6 `--summary-export` flag'i
   * ayrıca çağrılmaz (handleSummary yazımı yeterli, duplicate kaldırıldı).
   *
   * ESTIMATE_ONLY mode'da k6 default summary yetersiz (scenario'lar çalışmaz),
   * projeksiyon JSON'unu {...data, estimate} olarak export et.
   */
  const summaryPath = __ENV.SUMMARY_PATH || 'k6-summary.json';
  const body = ESTIMATE_ONLY
    ? { ...data, estimate: computeEstimateSummary() }
    : data;
  const stdoutExtra = ESTIMATE_ONLY
    ? `\n\n━━━ ESTIMATE PROJECTION ━━━\n${JSON.stringify(computeEstimateSummary(), null, 2)}\n`
    : '';
  return {
    stdout: `${textSummary(data)}${stdoutExtra}`,
    [summaryPath]: JSON.stringify(body, null, 2),
  };
}

function textSummary(data) {
  const phase = PHASE;
  const total = data.metrics.authz_persona_outcome?.values?.count || 0;
  const mismatch = data.metrics.authz_persona_mismatch?.values?.rate || 0;
  const errorRate = data.metrics.authz_http_error?.values?.rate || 0;
  const p95 = data.metrics.authz_persona_latency?.values?.['p(95)'] || 0;

  return [
    '',
    `━━━ k6 Zanzibar Persona Matrix — phase=${phase} ━━━`,
    `  Total outcomes:  ${total}`,
    `  Mismatch rate:   ${(mismatch * 100).toFixed(2)}%  (threshold <1%)`,
    `  HTTP error rate: ${(errorRate * 100).toFixed(3)}%  (threshold <0.5%)`,
    `  Latency p95:     ${p95.toFixed(1)}ms  (threshold <50ms)`,
    `━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━`,
    '',
  ].join('\n');
}
