#!/usr/bin/env node
/**
 * zanzibar-canary-setup.mjs
 *
 * CNS-20260415-004 (Codex) tasarımı: Idempotent seed — Keycloak + user-service +
 * permission-service + write-path version polling. Persona matrix k6 run'u için
 * gerekli 5 kullanıcı + 5 rol + granule + member + scope + persona token haritası
 * üretir.
 *
 * Flow:
 *   1. Keycloak admin token
 *   2. 5 persona user (KC + user-service internal/provision)
 *   3. 5 canary role (POST /api/v1/roles)
 *   4. Granule atama (PUT /api/v1/roles/{id}/granules)
 *   5. User-role member (POST /api/v1/roles/{id}/members)
 *   6. Scope atama (POST /api/v1/roles/users/{userId}/scopes)
 *   7. Super-admin OpenFGA org admin tuple (init.sh pattern, raw seed)
 *   8. Write-path version verification (toggle role granule → /authz/version bump)
 *   9. Per-persona token (password grant, stage-only canary-load client)
 *  10. Output: persona-tokens.json
 *
 * Modlar:
 *   --dry-run   : API çağrısı yapma, plan'ı yazdır (CI veya lokal prova için)
 *   --skip-kc   : Keycloak user create/sync atla (lokal permitAll test için)
 *   --skip-write-verify : Write-path version poll atla (hızlı local setup)
 *   --output <path> : persona-tokens.json yolu (default: .cache/zanzibar-canary/persona-tokens.json)
 *
 * Env:
 *   KC_BASE_URL, KC_REALM, KC_CANARY_CLIENT_ID, KC_CANARY_CLIENT_SECRET
 *   USER_SERVICE_URL (default http://localhost:8089)
 *   PERMISSION_SERVICE_URL (default http://localhost:8090)
 *   OPENFGA_URL (default http://localhost:4000)
 *   OPENFGA_STORE_ID
 *   ADMIN_TOKEN (opsiyonel — admin API çağrıları için; yoksa local permitAll varsayılır)
 *   CANARY_PASSWORD (persona user password; default "CanaryPass123!")
 *
 * Ref:
 *   - backend/scripts/keycloak/provision-user.sh:90-147 (client_credentials + provision)
 *   - backend/openfga/init.sh:62-67 (tuple write)
 *   - AccessControllerV1.java:163-229 (members + granules)
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

// ─────────────────────────────────────────────────────────────────────────────
// CLI args + env
// ─────────────────────────────────────────────────────────────────────────────

const parseArgs = () => {
  const args = process.argv.slice(2);
  const map = new Map();
  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i];
    if (arg.startsWith('--')) {
      const value = args[i + 1] && !args[i + 1].startsWith('--') ? args[i + 1] : 'true';
      if (value !== 'true') i += 1;
      map.set(arg, value);
    }
  }
  return map;
};

const args = parseArgs();
const arg = (k, def) => (args.has(k) ? args.get(k) : def);

const DRY_RUN = args.has('--dry-run') || process.env.DRY_RUN === '1';
const SKIP_KC = args.has('--skip-kc') || process.env.SKIP_KC === '1';
const SKIP_WRITE_VERIFY = args.has('--skip-write-verify') || process.env.SKIP_WRITE_VERIFY === '1';
const OUTPUT_PATH = arg('--output', path.resolve(process.cwd(), '.cache/zanzibar-canary/persona-tokens.json'));

const KC_BASE_URL = process.env.KC_BASE_URL || 'http://localhost:8081';
const KC_REALM = process.env.KC_REALM || 'serban';
const KC_CANARY_CLIENT_ID = process.env.KC_CANARY_CLIENT_ID || 'canary-load';
const KC_CANARY_CLIENT_SECRET = process.env.KC_CANARY_CLIENT_SECRET || '';
const USER_SERVICE_URL = process.env.USER_SERVICE_URL || 'http://localhost:8089';
const PERMISSION_SERVICE_URL = process.env.PERMISSION_SERVICE_URL || 'http://localhost:8090';
const OPENFGA_URL = process.env.OPENFGA_URL || 'http://localhost:4000';
const OPENFGA_STORE_ID = process.env.OPENFGA_STORE_ID || '';
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || '';
const CANARY_PASSWORD = process.env.CANARY_PASSWORD || 'CanaryPass123!';

// P1.8 (STORY-0320): canonical service-token path. Auth-service mints scoped
// service tokens via /oauth2/token client_credentials grant. Three distinct
// audiences consumed by this script:
//   - KC admin API        (Keycloak master realm, not auth-service — keeps ADMIN_TOKEN env)
//   - user-service internal  (audience=user-service, permissions=users:internal)
//   - permission-service     (audience=permission-service, permissions=permissions:read+write)
const AUTH_SERVICE_URL = process.env.AUTH_SERVICE_URL || 'http://localhost:8088';
const SERVICE_TOKEN_CLIENT_ID = process.env.SERVICE_TOKEN_CLIENT_ID || 'user-service';
const SERVICE_TOKEN_CLIENT_SECRET = process.env.SERVICE_TOKEN_CLIENT_SECRET
  || process.env.SERVICE_CLIENT_USER_SERVICE_SECRET
  || '';
// Optional explicit overrides (skip mint when provided, e.g. for local testing
// with static tokens). Normal flow lazy-mints via mintServiceToken().
const USER_SERVICE_INTERNAL_TOKEN_OVERRIDE = process.env.USER_SERVICE_INTERNAL_TOKEN || '';
const PERM_SERVICE_ADMIN_TOKEN_OVERRIDE = process.env.PERM_SERVICE_ADMIN_TOKEN || '';

// Legacy compat: explicit opt-in for Hybrid B ADMIN_TOKEN mode. Default is
// canonical (auth-service mint, fail-fast). CANARY_USE_LEGACY_ADMIN_TOKEN=1
// keeps the pre-P1.8 behaviour for environments that cannot reach
// auth-service yet (e.g. mid-migration). Codex verdict: implicit fallback
// masks misconfiguration; canonical must be the default.
const LEGACY_ADMIN_TOKEN_MODE = process.env.CANARY_USE_LEGACY_ADMIN_TOKEN === '1';

// ─────────────────────────────────────────────────────────────────────────────
// Persona tanımları — Codex CNS-004 tablosu
// ─────────────────────────────────────────────────────────────────────────────

const PERSONAS = [
  {
    key: 'super_admin',
    email: 'canary-super-admin@stage.local',
    name: 'Canary Super Admin',
    roleName: null, // ADMIN realm role, ayrı akış
    openFgaAdmin: true,
    scopes: [{ scopeType: 'COMPANY', scopeRefId: 1 }],
  },
  {
    key: 'read_only',
    email: 'canary-read-only@stage.local',
    name: 'Canary Read Only',
    roleName: 'CANARY_READ_ONLY',
    openFgaAdmin: false,
    scopes: [{ scopeType: 'COMPANY', scopeRefId: 1 }],
  },
  {
    key: 'restricted',
    email: 'canary-restricted@stage.local',
    name: 'Canary Restricted',
    roleName: 'CANARY_RESTRICTED',
    openFgaAdmin: false,
    scopes: [{ scopeType: 'COMPANY', scopeRefId: 1 }],
  },
  {
    key: 'multi_role_deny',
    email: 'canary-multi-role@stage.local',
    name: 'Canary Multi Role Deny',
    roleNames: ['CANARY_PURCHASE_MANAGER', 'CANARY_DENY_DELETE'],
    openFgaAdmin: false,
    scopes: [{ scopeType: 'COMPANY', scopeRefId: 1 }],
  },
  {
    key: 'scope_less',
    email: 'canary-scope-less@stage.local',
    name: 'Canary Scopeless',
    roleName: 'CANARY_SCOPELESS',
    openFgaAdmin: false,
    scopes: [], // KASITLI — scope yok
  },
];

// Rol granule tanımları — AccessControllerV1 PUT /granules body:
// { "permissions": [ { "type":"MODULE|ACTION|REPORT", "key":"...", "grant":"VIEW|MANAGE|ALLOW|DENY" } ] }
const ROLE_GRANULES = {
  CANARY_READ_ONLY: {
    description: 'Canary read-only (VIEW only, MANAGE yok)',
    permissions: [
      { type: 'MODULE', key: 'ACCESS',  grant: 'VIEW' },
      { type: 'MODULE', key: 'AUDIT',   grant: 'VIEW' },
      { type: 'MODULE', key: 'REPORT',  grant: 'VIEW' },
      { type: 'MODULE', key: 'THEME',   grant: 'VIEW' },
      { type: 'MODULE', key: 'COMPANY', grant: 'VIEW' },
    ],
  },
  CANARY_RESTRICTED: {
    description: 'Canary restricted (ACCESS/REPORT/COMPANY VIEW; THEME/AUDIT yok)',
    permissions: [
      { type: 'MODULE', key: 'ACCESS',  grant: 'VIEW' },
      { type: 'MODULE', key: 'REPORT',  grant: 'VIEW' },
      { type: 'MODULE', key: 'COMPANY', grant: 'VIEW' },
    ],
  },
  CANARY_PURCHASE_MANAGER: {
    description: 'Canary purchase manager (PURCHASE MANAGE + action CREATE_PO ALLOW)',
    permissions: [
      { type: 'MODULE', key: 'PURCHASE',   grant: 'MANAGE' },
      { type: 'MODULE', key: 'REPORT',     grant: 'VIEW' },
      { type: 'ACTION', key: 'CREATE_PO',  grant: 'ALLOW' },
    ],
  },
  CANARY_DENY_DELETE: {
    description: 'Canary deny delete (action DELETE_PO DENY)',
    permissions: [
      { type: 'ACTION', key: 'DELETE_PO', grant: 'DENY' },
    ],
  },
  CANARY_SCOPELESS: {
    description: 'Canary scopeless (COMPANY/REPORT VIEW; scope atanmadı)',
    permissions: [
      { type: 'MODULE', key: 'COMPANY', grant: 'VIEW' },
      { type: 'MODULE', key: 'REPORT',  grant: 'VIEW' },
    ],
  },
};

// ─────────────────────────────────────────────────────────────────────────────
// HTTP helpers
// ─────────────────────────────────────────────────────────────────────────────

async function fetchJson(url, opts = {}) {
  if (DRY_RUN) {
    log(`[DRY_RUN] ${opts.method || 'GET'} ${url}${opts.body ? ` body=${truncate(opts.body)}` : ''}`);
    return { __dryRun: true, status: 200, body: null };
  }
  const res = await fetch(url, {
    ...opts,
    headers: { 'Content-Type': 'application/json', ...(opts.headers || {}) },
  });
  const text = await res.text();
  let body = null;
  if (text) {
    try { body = JSON.parse(text); } catch { body = text; }
  }
  return { status: res.status, body, ok: res.ok };
}

function truncate(s, max = 120) {
  if (typeof s !== 'string') s = JSON.stringify(s);
  return s.length > max ? `${s.slice(0, max)}...` : s;
}

function log(msg) {
  const ts = new Date().toISOString().slice(11, 19);
  console.log(`[${ts}] ${msg}`);
}

function warn(msg) {
  const ts = new Date().toISOString().slice(11, 19);
  console.warn(`[${ts}] ⚠️  ${msg}`);
}

function fail(msg) {
  console.error(`[FAIL] ${msg}`);
  process.exit(1);
}

// ─────────────────────────────────────────────────────────────────────────────
// Keycloak helpers
// ─────────────────────────────────────────────────────────────────────────────

async function kcAdminToken() {
  if (!KC_CANARY_CLIENT_SECRET && !ADMIN_TOKEN) {
    warn('KC_CANARY_CLIENT_SECRET + ADMIN_TOKEN yok — Keycloak admin API atlanacak (SKIP_KC=1 varsayılır)');
    return null;
  }
  if (ADMIN_TOKEN) return ADMIN_TOKEN;
  const url = `${KC_BASE_URL}/realms/master/protocol/openid-connect/token`;
  const body = new URLSearchParams({
    grant_type: 'client_credentials',
    client_id: KC_CANARY_CLIENT_ID,
    client_secret: KC_CANARY_CLIENT_SECRET,
  });
  if (DRY_RUN) {
    log(`[DRY_RUN] Keycloak admin token alınacak: ${url}`);
    return 'DRY_RUN_TOKEN';
  }
  const res = await fetch(url, { method: 'POST', body, headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
  if (!res.ok) fail(`Keycloak admin token alınamadı: ${res.status}`);
  const data = await res.json();
  return data.access_token;
}

async function kcUserPasswordToken(email) {
  if (!KC_CANARY_CLIENT_SECRET) {
    warn(`${email} için password token atlanıyor — canary client secret yok`);
    return null;
  }
  const url = `${KC_BASE_URL}/realms/${KC_REALM}/protocol/openid-connect/token`;
  const body = new URLSearchParams({
    grant_type: 'password',
    client_id: KC_CANARY_CLIENT_ID,
    client_secret: KC_CANARY_CLIENT_SECRET,
    username: email,
    password: CANARY_PASSWORD,
  });
  if (DRY_RUN) {
    log(`[DRY_RUN] ${email} password token alınacak`);
    return 'DRY_RUN_USER_TOKEN';
  }
  const res = await fetch(url, { method: 'POST', body, headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
  if (!res.ok) {
    warn(`${email} password token başarısız: ${res.status} (devam ediliyor)`);
    return null;
  }
  const data = await res.json();
  return data.access_token;
}

async function kcEnsureUser(adminToken, persona) {
  if (SKIP_KC) {
    log(`[SKIP_KC] ${persona.email} Keycloak create atlandı`);
    return null;
  }
  // KC admin API: GET /admin/realms/{realm}/users?email=...
  const searchUrl = `${KC_BASE_URL}/admin/realms/${KC_REALM}/users?email=${encodeURIComponent(persona.email)}&exact=true`;
  const found = await fetchJson(searchUrl, { headers: { Authorization: `Bearer ${adminToken}` } });
  if (found.ok && Array.isArray(found.body) && found.body.length > 0) {
    log(`KC user mevcut: ${persona.email} (id=${found.body[0].id})`);
    return found.body[0].id;
  }
  // Create
  const createUrl = `${KC_BASE_URL}/admin/realms/${KC_REALM}/users`;
  const payload = {
    username: persona.email,
    email: persona.email,
    enabled: true,
    emailVerified: true,
    firstName: persona.name.split(' ')[0] || 'Canary',
    lastName: persona.name.split(' ').slice(1).join(' ') || 'User',
    credentials: [{ type: 'password', value: CANARY_PASSWORD, temporary: false }],
  };
  const created = await fetchJson(createUrl, {
    method: 'POST',
    body: JSON.stringify(payload),
    headers: { Authorization: `Bearer ${adminToken}` },
  });
  if (!created.ok && created.status !== 201) {
    warn(`${persona.email} KC create başarısız: ${created.status} (devam)`);
    return null;
  }
  log(`KC user oluşturuldu: ${persona.email}`);
  // Location header'dan id çekmek yerine re-fetch
  const refetch = await fetchJson(searchUrl, { headers: { Authorization: `Bearer ${adminToken}` } });
  return refetch.body?.[0]?.id || null;
}

// ─────────────────────────────────────────────────────────────────────────────
// auth-service service-token mint (P1.8 canonical path)
// ─────────────────────────────────────────────────────────────────────────────

let _userServiceInternalToken = null;
let _permServiceAdminToken = null;

/**
 * Mint a service token via auth-service /oauth2/token (client_credentials).
 * Repeats the `permissions` form field per spec so multi-permission requests
 * map to a list claim on the minted JWT.
 */
async function mintServiceToken(audience, permissions) {
  if (DRY_RUN) {
    // DRY_RUN contract: do not make network calls, just log the plan.
    // Returning a sentinel token keeps downstream Bearer header construction
    // intact; fetchJson() short-circuits in DRY_RUN so no live request fires.
    log(`[DRY_RUN] mintServiceToken audience=${audience} permissions=${(permissions || []).join(',') || '(none)'}`);
    return 'dry-run-service-token';
  }
  if (!SERVICE_TOKEN_CLIENT_SECRET) {
    throw new Error(
      `SERVICE_TOKEN_CLIENT_SECRET (or SERVICE_CLIENT_USER_SERVICE_SECRET) is empty — ` +
      `cannot mint service token for audience=${audience}. Set the env or provide ` +
      `USER_SERVICE_INTERNAL_TOKEN / PERM_SERVICE_ADMIN_TOKEN override.`,
    );
  }
  const body = new URLSearchParams();
  body.append('grant_type', 'client_credentials');
  body.append('audience', audience);
  (permissions || []).forEach((p) => body.append('permissions', p));

  const basic = Buffer
    .from(`${SERVICE_TOKEN_CLIENT_ID}:${SERVICE_TOKEN_CLIENT_SECRET}`)
    .toString('base64');

  const res = await fetchJson(`${AUTH_SERVICE_URL}/oauth2/token`, {
    method: 'POST',
    body: body.toString(),
    headers: {
      'Authorization': `Basic ${basic}`,
      'Content-Type': 'application/x-www-form-urlencoded',
    },
  });
  if (!res.ok || !res.body?.access_token) {
    throw new Error(
      `auth-service mint failed audience=${audience}: ${res.status} ${truncate(res.body)}`,
    );
  }
  return res.body.access_token;
}

async function getUserServiceInternalToken() {
  if (USER_SERVICE_INTERNAL_TOKEN_OVERRIDE) return USER_SERVICE_INTERNAL_TOKEN_OVERRIDE;
  if (!_userServiceInternalToken) {
    _userServiceInternalToken = await mintServiceToken('user-service', ['users:internal']);
    log('minted service token: audience=user-service permissions=users:internal');
  }
  return _userServiceInternalToken;
}

async function getPermServiceAdminToken() {
  if (PERM_SERVICE_ADMIN_TOKEN_OVERRIDE) return PERM_SERVICE_ADMIN_TOKEN_OVERRIDE;
  if (!_permServiceAdminToken) {
    _permServiceAdminToken = await mintServiceToken(
      'permission-service',
      ['permissions:read', 'permissions:write'],
    );
    log('minted service token: audience=permission-service permissions=permissions:read,write');
  }
  return _permServiceAdminToken;
}

// ─────────────────────────────────────────────────────────────────────────────
// user-service helpers
// ─────────────────────────────────────────────────────────────────────────────

async function userServiceProvision(persona) {
  const url = `${USER_SERVICE_URL}/api/v1/users/internal/provision`;
  const payload = {
    email: persona.email,
    name: persona.name,
    role: persona.openFgaAdmin ? 'ADMIN' : 'USER',
    enabled: true,
  };
  const headers = {};
  // P1.8: canonical path (auth-service mint, audience=user-service, perm=users:internal).
  // Fail-fast by default — misconfigured auth-service / JWKS / secret surfaces
  // immediately rather than being masked by the legacy admin token.
  if (LEGACY_ADMIN_TOKEN_MODE) {
    if (!ADMIN_TOKEN) {
      throw new Error(
        'CANARY_USE_LEGACY_ADMIN_TOKEN=1 set but ADMIN_TOKEN env is empty — ' +
        'either unset the flag (canonical mint) or provide ADMIN_TOKEN.',
      );
    }
    warn('LEGACY mode (CANARY_USE_LEGACY_ADMIN_TOKEN=1): using ADMIN_TOKEN for user-service internal — deprecated, migrate to auth-service mint.');
    headers.Authorization = `Bearer ${ADMIN_TOKEN}`;
  } else {
    headers.Authorization = `Bearer ${await getUserServiceInternalToken()}`;
  }
  const res = await fetchJson(url, { method: 'POST', body: JSON.stringify(payload), headers });
  if (DRY_RUN) return { userId: 0 };
  if (!res.ok) {
    warn(`user-service provision başarısız (${persona.email}): ${res.status} ${truncate(res.body)}`);
    return { userId: null };
  }
  const userId = res.body?.id ?? res.body?.userId ?? null;
  log(`user-service provision ok: ${persona.email} → userId=${userId}`);
  return { userId };
}

// ─────────────────────────────────────────────────────────────────────────────
// permission-service helpers
// ─────────────────────────────────────────────────────────────────────────────

async function psAuthHeaders() {
  const h = {};
  // P1.8: canonical path (auth-service mint, audience=permission-service,
  // perm=permissions:read+write). Same fail-fast + opt-in legacy semantics
  // as userServiceProvision().
  if (LEGACY_ADMIN_TOKEN_MODE) {
    if (!ADMIN_TOKEN) {
      throw new Error(
        'CANARY_USE_LEGACY_ADMIN_TOKEN=1 set but ADMIN_TOKEN env is empty — ' +
        'either unset the flag (canonical mint) or provide ADMIN_TOKEN.',
      );
    }
    warn('LEGACY mode (CANARY_USE_LEGACY_ADMIN_TOKEN=1): using ADMIN_TOKEN for permission-service — deprecated.');
    h.Authorization = `Bearer ${ADMIN_TOKEN}`;
  } else {
    h.Authorization = `Bearer ${await getPermServiceAdminToken()}`;
  }
  return h;
}

async function psListRoles() {
  const res = await fetchJson(`${PERMISSION_SERVICE_URL}/api/v1/roles`, { headers: await psAuthHeaders() });
  if (DRY_RUN) return [];
  if (!res.ok) return [];
  return res.body?.items ?? [];
}

async function psEnsureRole(name, description) {
  const existing = await psListRoles();
  const found = existing.find((r) => r.name === name);
  if (found) {
    log(`Rol mevcut: ${name} (id=${found.id})`);
    return found.id;
  }
  const res = await fetchJson(`${PERMISSION_SERVICE_URL}/api/v1/roles`, {
    method: 'POST',
    body: JSON.stringify({ name, description }),
    headers: await psAuthHeaders(),
  });
  if (DRY_RUN) return -1;
  if (!res.ok) {
    warn(`Rol oluşturma başarısız (${name}): ${res.status} ${truncate(res.body)}`);
    return null;
  }
  const id = res.body?.id ?? null;
  log(`Rol oluşturuldu: ${name} (id=${id})`);
  return id;
}

async function psUpdateGranules(roleId, granules) {
  const res = await fetchJson(`${PERMISSION_SERVICE_URL}/api/v1/roles/${roleId}/granules`, {
    method: 'PUT',
    body: JSON.stringify({ permissions: granules }),
    headers: await psAuthHeaders(),
  });
  if (DRY_RUN) return true;
  if (!res.ok) {
    warn(`Granule update başarısız (roleId=${roleId}): ${res.status} ${truncate(res.body)}`);
    return false;
  }
  log(`Granule güncel: roleId=${roleId} count=${granules.length}`);
  return true;
}

/**
 * CNS-20260416-001 B1 fix:
 * Eski plan `POST /api/v1/roles/{id}/members` + `POST /api/v1/roles/users/{id}/scopes`
 * iki ayrı çağrıydı; ikincisi `permissionCode` zorunlu kılıyor (UserScopeService.addScope
 * permission lookup yapar → canary'de her permission için ayrı scope assignment
 * gerektirir, karmaşık + kırılgan).
 *
 * Daha temiz yol: `POST /api/v1/authz/users/{userId}/assignments` (AuthorizationControllerV1).
 * Tek-shot role+scope assignment, TupleSync'i tetikler, version'ı BİR kez bump eder.
 * UserAssignmentRequestDto: { roleIds: [Long], scopes: { companyIds, projectIds,
 * warehouseIds, branchIds } }. permissionCode GEREKTİRMEZ.
 */
async function psAssignUserRolesAndScopes(userId, roleIds, scopeAssignment) {
  const payload = {
    roleIds,
    scopes: scopeAssignment ?? {
      companyIds: [],
      projectIds: [],
      warehouseIds: [],
      branchIds: [],
    },
  };
  const res = await fetchJson(`${PERMISSION_SERVICE_URL}/api/v1/authz/users/${userId}/assignments`, {
    method: 'POST',
    body: JSON.stringify(payload),
    headers: await psAuthHeaders(),
  });
  if (DRY_RUN) return true;
  if (!res.ok) {
    warn(`Assignment başarısız (user=${userId}, roles=${roleIds.join(',')}): ${res.status} ${truncate(res.body)}`);
    return false;
  }
  log(`Assignment ok: user=${userId} roles=[${roleIds.join(',')}] companies=[${(scopeAssignment?.companyIds ?? []).join(',')}]`);
  return true;
}

async function psGetAuthzVersion() {
  const res = await fetchJson(`${PERMISSION_SERVICE_URL}/api/v1/authz/version`, { headers: await psAuthHeaders() });
  if (DRY_RUN) return 0;
  if (!res.ok) return null;
  return res.body?.authzVersion ?? null;
}

// ─────────────────────────────────────────────────────────────────────────────
// OpenFGA helpers
// ─────────────────────────────────────────────────────────────────────────────

async function openFgaWriteTuple(userRef, relation, objectRef) {
  if (!OPENFGA_STORE_ID) {
    warn('OPENFGA_STORE_ID yok — tuple write atlandı');
    return false;
  }
  const url = `${OPENFGA_URL}/stores/${OPENFGA_STORE_ID}/write`;
  const payload = { writes: { tuple_keys: [{ user: userRef, relation, object: objectRef }] } };
  const res = await fetchJson(url, { method: 'POST', body: JSON.stringify(payload) });
  if (DRY_RUN) return true;
  if (!res.ok) {
    // 400 — already exists olabilir, idempotent kabul et
    if (res.status === 400 && JSON.stringify(res.body).includes('already')) {
      log(`OpenFGA tuple zaten var: ${userRef} ${relation} ${objectRef}`);
      return true;
    }
    warn(`OpenFGA tuple write başarısız: ${userRef} ${relation} ${objectRef} → ${res.status}`);
    return false;
  }
  log(`OpenFGA tuple yazıldı: ${userRef} ${relation} ${objectRef}`);
  return true;
}

// ─────────────────────────────────────────────────────────────────────────────
// Write-path verification — CNS-004 Codex sırası
// ─────────────────────────────────────────────────────────────────────────────

async function verifyWritePath(roleIds) {
  if (SKIP_WRITE_VERIFY) {
    log('[SKIP_WRITE_VERIFY] Write-path version poll atlandı');
    return true;
  }
  const toggleRole = roleIds.CANARY_READ_ONLY;
  if (!toggleRole) {
    warn('Toggle role için CANARY_READ_ONLY yok — write-path verify atlandı');
    return false;
  }
  log('Write-path version verification başlıyor (outbox poller 30s → timeout 120s)...');

  const baseline = await psGetAuthzVersion();
  log(`  baseline authzVersion=${baseline}`);

  // CNS-20260416-001 M1 fix: timeout 60s → 120s (staging jitter için marj)
  const WRITE_POLL_TIMEOUT_MS = 120_000;

  const orig = ROLE_GRANULES.CANARY_READ_ONLY.permissions;

  // 1) Granule flip (ACCESS VIEW → DENY)
  const flipped = orig.map((g) =>
    g.key === 'ACCESS' ? { ...g, grant: 'DENY' } : g,
  );
  await psUpdateGranules(toggleRole, flipped);

  const bumped = await pollVersionBump(baseline, WRITE_POLL_TIMEOUT_MS);
  if (!bumped) {
    warn('Version bump (flip) 120s içinde gerçekleşmedi — outbox poller çalışmıyor olabilir');
    // Flip bump bulunamadı: restore'u yine dene ama bu durum warning, fatal değil
    // (staging'de bump başka iş yüzünden gecikmiş olabilir)
  } else {
    log(`  version bump OK (${baseline} → ${bumped})`);
  }

  // 2) Restore (orig state)
  // CNS-20260416-001 Q3 fix: psUpdateGranules return değerini kontrol et.
  // Restore PUT fail olursa version bump olmasa da canary ortamı DENY state'te kalır.
  const restoreUpdateOk = await psUpdateGranules(toggleRole, orig);
  if (!restoreUpdateOk) {
    console.error('[FATAL] Granule restore PUT başarısız oldu — canary DENY state\'te kalmış olabilir.');
    console.error(`  Manuel kontrol: curl -s ${PERMISSION_SERVICE_URL}/api/v1/roles/${toggleRole}`);
    process.exit(1);
  }
  const restored = await pollVersionBump(bumped ?? baseline, WRITE_POLL_TIMEOUT_MS);

  // CNS-20260416-001 M1 fix: restore FAIL olursa fatal.
  // Canary ortamı DENY state'te kalamaz — persona matrix cold run'da yanlış sonuç üretir.
  if (!restored) {
    console.error('[FATAL] Granule restore version bump 120s içinde doğrulanamadı.');
    console.error(`  CANARY_READ_ONLY rolü DENY state'te kalmış olabilir — manuel kontrol zorunlu:`);
    console.error(`  curl -s ${PERMISSION_SERVICE_URL}/api/v1/authz/version`);
    console.error(`  curl -s ${PERMISSION_SERVICE_URL}/api/v1/roles/${toggleRole}`);
    process.exit(1);
  }
  log(`  version restore OK (${bumped ?? baseline} → ${restored})`);
  return true;
}

async function pollVersionBump(prev, timeoutMs) {
  if (DRY_RUN) return (prev ?? 0) + 1;
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const cur = await psGetAuthzVersion();
    if (cur != null && cur > (prev ?? 0)) return cur;
    await sleep(2000);
  }
  return null;
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

// ─────────────────────────────────────────────────────────────────────────────
// Main
// ─────────────────────────────────────────────────────────────────────────────

async function main() {
  log('━━━ zanzibar-canary-setup (CNS-20260415-004) ━━━');
  log(`  DRY_RUN=${DRY_RUN}  SKIP_KC=${SKIP_KC}  SKIP_WRITE_VERIFY=${SKIP_WRITE_VERIFY}`);
  log(`  KC=${KC_BASE_URL}/${KC_REALM}`);
  log(`  US=${USER_SERVICE_URL}  PS=${PERMISSION_SERVICE_URL}  FGA=${OPENFGA_URL}`);

  // 1. Keycloak admin token
  const adminToken = await kcAdminToken();

  // 2. Persona user'ları KC + user-service
  const personaUserIds = {};
  for (const persona of PERSONAS) {
    if (adminToken) await kcEnsureUser(adminToken, persona);
    const { userId } = await userServiceProvision(persona);
    personaUserIds[persona.key] = userId;
  }

  // 3. Canary rol'leri oluştur
  const roleIds = {};
  for (const [name, cfg] of Object.entries(ROLE_GRANULES)) {
    const id = await psEnsureRole(name, cfg.description);
    roleIds[name] = id;
  }

  // 4. Granule atama
  for (const [name, cfg] of Object.entries(ROLE_GRANULES)) {
    if (roleIds[name]) await psUpdateGranules(roleIds[name], cfg.permissions);
  }

  // 5. Single-shot role+scope assignment (B1 fix: /api/v1/authz/users/{id}/assignments)
  for (const persona of PERSONAS) {
    const userId = personaUserIds[persona.key];
    if (!userId) continue;

    const roleNames = persona.roleNames ?? (persona.roleName ? [persona.roleName] : []);
    const personaRoleIds = roleNames
      .map((n) => roleIds[n])
      .filter((id) => id != null && id !== -1); // DRY_RUN placeholder -1 atla

    // Persona scopes → ScopeAssignmentDto {companyIds, projectIds, warehouseIds, branchIds}
    const scopeDto = {
      companyIds: [],
      projectIds: [],
      warehouseIds: [],
      branchIds: [],
    };
    for (const s of persona.scopes ?? []) {
      if (s.scopeType === 'COMPANY')   scopeDto.companyIds.push(s.scopeRefId);
      if (s.scopeType === 'PROJECT')   scopeDto.projectIds.push(s.scopeRefId);
      if (s.scopeType === 'WAREHOUSE') scopeDto.warehouseIds.push(s.scopeRefId);
      if (s.scopeType === 'BRANCH')    scopeDto.branchIds.push(s.scopeRefId);
    }

    if (personaRoleIds.length > 0) {
      await psAssignUserRolesAndScopes(userId, personaRoleIds, scopeDto);
    } else if (persona.openFgaAdmin) {
      // super-admin'in rol atanmadan da scope'u olsun (ORG admin tuple ayrıca yazılacak)
      await psAssignUserRolesAndScopes(userId, [], scopeDto);
    }

    // Super-admin raw OpenFGA org-admin tuple (D-008 istisnai relation — role path dışı)
    if (persona.openFgaAdmin) {
      await openFgaWriteTuple(`user:${userId}`, 'admin', 'organization:default');
    }
  }

  // 7. Write-path version verification
  await verifyWritePath(roleIds);

  // 8. Per-persona token üret (password grant, stage-only canary-load client)
  const tokens = {};
  for (const persona of PERSONAS) {
    const tok = await kcUserPasswordToken(persona.email);
    if (tok) tokens[persona.key] = tok;
  }

  // 9. Output
  const outDir = path.dirname(OUTPUT_PATH);
  if (!fs.existsSync(outDir)) fs.mkdirSync(outDir, { recursive: true });
  const output = {
    generatedAt: new Date().toISOString(),
    ref: 'CNS-20260415-004',
    mode: DRY_RUN ? 'DRY_RUN' : 'LIVE',
    personas: PERSONAS.map((p) => ({
      key: p.key,
      email: p.email,
      userId: personaUserIds[p.key] ?? null,
      hasToken: Boolean(tokens[p.key]),
    })),
    tokens, // hassas — .cache/ gitignore kapsamında
  };
  fs.writeFileSync(OUTPUT_PATH, JSON.stringify(output, null, 2));
  log(`Output yazıldı: ${OUTPUT_PATH}`);
  log(`  Persona: ${Object.keys(tokens).length}/${PERSONAS.length} token üretildi`);
  log('━━━ setup TAMAM ━━━');
}

main().catch((err) => {
  console.error('[FATAL]', err);
  process.exit(1);
});
