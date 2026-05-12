# RB-impersonation-live-smoke — User Impersonation Acceptance Matrix

> **Purpose**: Reproducible live smoke matrix for the User Impersonation feature. Run after every deploy or as a sanity check before prod cutover.
> **Scope**: 6 cells of the Session 47 acceptance matrix verified live on testai 2026-05-12 ~22:30 UTC+3. Each cell isolated, audit-bearing, and DB-checkable.
> **Format**: D28 5-alan-compatible (Bağlam, İddia, İspat, İspatlamaz, Bilinen Boşluk).
> **Codex thread**: `019e1bed-637e-74e0-815a-fa2b83943acc` (Session 47) + `019e1dc5-3f0d-7b12-a76e-d0b1b87f6907` (Session 47 stabilization)

---

## Prerequisites

- Admin user logged in with browser session (cookies set).
- `kc_subject` backfill applied for all 5 test users (`RB-kc-subject-backfill.md`).
- Admin user has KC `userId` attribute set (see Session 46 KC operator action note).
- testai cluster auth-service + user-service deployed with kcSubject DTOs (PR #159 + #160 + #161 + #162).

---

## Matrix

10 cells. **M2 is the canonical happy/stop pair** verified end-to-end during the Session 47 UX overhaul (admin → testuser, 2026-05-12 ~19:17 UTC+3); cells M1, M3–M10 below extend coverage to negative gates, alternate target types, and policy edges.

| Cell | Scenario | Method | Expected | Audit |
|---|---|---|---|---|
| **M1** | admin → admin (self) | UI: button hidden + JS bypass | FE button absent, BE 403 `SELF_IMPERSONATION_FORBIDDEN` | `IMPERSONATION_BLOCKED` |
| **M2** | admin → primary ADMIN target (full lifecycle: start + identity swap + banner + stop) | UI button | 201 + identity swap + banner mount → stop 204 + revert + banner unmount | `IMPERSONATION_STARTED` + `IMPERSONATION_REVOKED` (same session_id) |
| **M3** | admin → alt admin | JS fetch | 201, session ACTIVE | `IMPERSONATION_STARTED` |
| **M4** | admin → USER role target | UI button | 201 + identity swap + grid 403 for admin features | `IMPERSONATION_STARTED` |
| **M5** | admin → 60min-config user | JS fetch | 201, `expiresAt` = now + 60min (TTL is fixed, NOT target.sessionTimeoutMinutes) | `IMPERSONATION_STARTED` |
| **M6** | admin → disabled user | JS fetch | 403 `TARGET_USER_DISABLED` BEFORE KC token-exchange | `IMPERSONATION_BLOCKED` |
| **M7** | reason < 10 char | JS fetch | 400 `VALIDATION_ERROR` with `fieldErrors[reason]` (Spring `@Size` localized to Turkish) | none |
| **M8** | concurrent start | JS fetch (admin already has ACTIVE session) | 409 `ACTIVE_IMPERSONATION_EXISTS` | none |
| **M9** | session timeout | DB force-expire `expires_at` + `/active` lookup | 204 No Content, `sweepExpired` flips to `EXPIRED` | `IMPERSONATION_EXPIRED` (if sweeper ran) |
| **M10** | banner viewport overflow | Resize window 768x900 | banner stop button position correct, no overflow | none |

---

## Step-by-step (run from browser DevTools console while logged in as admin)

### M2 — canonical happy/stop lifecycle (Session 47 reference run)

This is the full E2E reference flow. Run via UI button — not raw JS — because the FE `impersonation-orchestration.ts` (PR #411) is what writes the impersonation cookie and triggers the authz state refresh that produces the identity swap. Raw `fetch` calls create the BE session correctly but do NOT swap FE identity (see NOTE #2 below).

1. Open User Management → İşlemler dropdown on **primary ADMIN target row** (Session 47 reference: `testuser@testai.acik.com`).
2. Click "Detayı Görüntüle" → drawer opens.
3. Click "Impersonate this user" → orange button at top.
4. Enter reason ≥10 char → click "Impersonate başlat".
5. Verify:
   - Network: `POST /api/v1/impersonation/sessions` returns **201** with `sessionId` + `expiresAt = now + 60min`.
   - Header right corner swaps from admin display name to target's display name (e.g. "TU Test User").
   - Banner mounts: `[data-testid="impersonation-banner"]` with text "⚠ {admin email} olarak {target email} adına işlem yapıyorsun (oturum 59 dk içinde sona erer)."
6. Click banner "Impersonation'ı durdur" button:
   - Network: `POST /api/v1/impersonation/sessions/{sid}/revoke` returns **204**.
   - Header reverts to admin display name.
   - Banner unmounts.
7. Verify DB:
```sql
SELECT id, event_type, target_email, impersonation_session_id
FROM permission_audit_events WHERE impersonation_session_id = '<SID>'
ORDER BY id;
-- EXPECT: IMPERSONATION_STARTED + IMPERSONATION_REVOKED (same session_id)

SELECT id, status, started_at, ended_at, ended_reason
FROM impersonation_sessions WHERE id = '<SID>';
-- EXPECT: status=STOPPED, ended_reason=USER_STOP_FROM_BANNER
```

### M1 — self-impersonation guard

**Frontend (must not render button):**
```js
// Navigate to admin's own row → drawer → button must be ABSENT
const btn = document.querySelector('[data-testid="impersonate-action"]');
console.assert(btn === null, 'M1 FE bypass: button should be hidden for self row');
```

**Backend (FE bypass attempt):**
```js
fetch('/api/v1/impersonation/sessions', {
  method: 'POST', credentials: 'include',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({targetUserId: <ADMIN_USER_ID>, reason: 'M1 BE self-guard bypass test'})
}).then(r => r.json().then(j => console.log('M1:', r.status, j.errorCode)));
// EXPECT: 403 SELF_IMPERSONATION_FORBIDDEN
```

**DB audit:**
```sql
-- platform-pg-test
SELECT id, event_type, target_user_id, details
FROM permission_db.permission_audit_events
WHERE event_type = 'IMPERSONATION_BLOCKED'
ORDER BY id DESC LIMIT 1;
-- EXPECT: target_user_id=<ADMIN_USER_ID>, details contains "Cannot impersonate your own account"
```

### M3 — alternate admin target

```js
fetch('/api/v1/impersonation/sessions', {
  method: 'POST', credentials: 'include',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({targetUserId: <ALT_ADMIN_USER_ID>, reason: 'M3 alternate admin happy path smoke'})
}).then(r => r.json().then(j => console.log('M3:', r.status, j.sessionId, j.expiresAt)));
// EXPECT: 201, sessionId present, expiresAt = now + 60min
```

**Cleanup:**
```js
fetch(`/api/v1/impersonation/sessions/${SID}/revoke`, {method: 'POST', credentials: 'include'});
```

### M4 — USER role target with authz reload

Use UI button (not JS) to trigger the FE orchestration that swaps the auth cookie and refreshes the authz state.

1. Click İşlemler → Detayı Görüntüle on USER row.
2. Click "Impersonate this user" button.
3. Enter reason ≥10 char + submit.
4. Verify header swaps to target user display name.
5. Verify `/api/v1/authz/me` returns target's `userId`, `superAdmin: false`.
6. Verify admin features (e.g., users grid) return 403 / "yetkiniz bulunmuyor".

```js
fetch('/api/v1/authz/me', {credentials: 'include'})
  .then(r => r.json())
  .then(me => console.log('M4 during impersonation:', me.userId, me.subscriberId, me.superAdmin));
// EXPECT: userId=<TARGET_USER_ID>, superAdmin=false
```

7. Click banner stop button.
8. Verify header reverts to admin.

```js
fetch('/api/v1/authz/me', {credentials: 'include'})
  .then(r => r.json())
  .then(me => console.log('M4 after stop:', me.userId, me.subscriberId, me.superAdmin));
// EXPECT: userId=<ADMIN_USER_ID>, superAdmin=true
```

### M5 — 60min TTL fixed

```js
const t0 = new Date();
fetch('/api/v1/impersonation/sessions', {
  method: 'POST', credentials: 'include',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({targetUserId: <USER_60MIN_CONFIG>, reason: 'M5 TTL verification 60min fixed not target session timeout'})
}).then(r => r.json().then(j => {
  const expiresAt = new Date(j.expiresAt);
  const ttlMin = (expiresAt - t0) / 1000 / 60;
  console.log('M5 TTL:', ttlMin, 'min (expected ~60)');
  fetch(`/api/v1/impersonation/sessions/${j.sessionId}/revoke`, {method: 'POST', credentials: 'include'});
}));
```

### M6 — disabled user gate

> **Operator note**: temporarily disable a test user. Re-enable IMMEDIATELY after the smoke. Do NOT disable shared cluster users without coordinating other sessions.

```bash
# Disable (operator)
docker exec platform-pg-test psql -U platform -d users_db -c \
  "UPDATE users SET enabled=false WHERE id=<TEST_USER_ID> RETURNING id, email, enabled"
```

```js
fetch('/api/v1/impersonation/sessions', {
  method: 'POST', credentials: 'include',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({targetUserId: <TEST_USER_ID>, reason: 'M6 disabled target user 403 TARGET_USER_DISABLED gate'})
}).then(r => r.json().then(j => console.log('M6:', r.status, j.errorCode)));
// EXPECT: 403 TARGET_USER_DISABLED
```

```bash
# Re-enable (operator)
docker exec platform-pg-test psql -U platform -d users_db -c \
  "UPDATE users SET enabled=true WHERE id=<TEST_USER_ID> RETURNING id, email, enabled"
```

### M7 — reason validation

```js
fetch('/api/v1/impersonation/sessions', {
  method: 'POST', credentials: 'include',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({targetUserId: <ANY_USER_ID>, reason: 'short'})
}).then(r => r.text().then(t => console.log('M7:', r.status, t.substring(0, 300))));
// EXPECT: 400, body shape: {"error":"VALIDATION_ERROR","fieldErrors":[{"field":"reason","message":"boyut '10' ile '500' arasında olmalı"}]}
```

> **NOTE (FE error mapping)**: The validation error shape differs from the impersonation `errorCode` shape (`MethodArgumentNotValidException` handled by `GlobalExceptionHandler.handleValidation`). The FE `ERROR_CODE_MESSAGES` map keyed off `errorCode` will not match — UX falls back to generic error. Follow-up: extend FE error mapping to recognize `error: "VALIDATION_ERROR"` + `fieldErrors[]` shape and surface field-level message.

### M8 — concurrent session policy

```js
// 1. Start an ACTIVE session
const start1 = await fetch('/api/v1/impersonation/sessions', {
  method: 'POST', credentials: 'include',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({targetUserId: <USER_A>, reason: 'M8 concurrent test session 1'})
});
const s1 = await start1.json();

// 2. Attempt concurrent start to different target
const start2 = await fetch('/api/v1/impersonation/sessions', {
  method: 'POST', credentials: 'include',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({targetUserId: <USER_B>, reason: 'M8 concurrent test session 2 must fail 409'})
});
const s2 = await start2.json();
console.log('M8:', start2.status, s2.errorCode);
// EXPECT: 409, errorCode=ACTIVE_IMPERSONATION_EXISTS

// 3. Cleanup
await fetch(`/api/v1/impersonation/sessions/${s1.sessionId}/revoke`, {method: 'POST', credentials: 'include'});
```

### M9 — session timeout force-expire

```bash
# Operator: set expires_at to past for an existing ACTIVE session
docker exec platform-pg-test psql -U platform -d permission_db -c \
  "UPDATE impersonation_sessions SET expires_at = now() - interval '5 min' WHERE id = '<SID>' RETURNING id, status, expires_at"
```

```js
// FE-side: /active should now return 204 (no ACTIVE session)
fetch('/api/v1/impersonation/sessions/active', {credentials: 'include'})
  .then(r => console.log('M9 /active after force-expire:', r.status));
// EXPECT: 204 No Content (sweeper flipped status to EXPIRED or active query filters expired)
```

```bash
# Verify sweeper or query filter caught it
docker exec platform-pg-test psql -U platform -d permission_db -c \
  "SELECT id, status, expires_at FROM impersonation_sessions WHERE id = '<SID>'"
# EXPECT: status=EXPIRED (or remains ACTIVE if sweeper cron hasn't run — in which case the FE filters expires_at)
```

### M10 — banner viewport overflow

1. Open DevTools → Device Toolbar (Cmd+Shift+M).
2. Set responsive width to **768x900** (tablet portrait).
3. Start an impersonation session via UI button.
4. Verify banner stops button (`[data-testid="impersonation-stop-btn"]`) is fully visible (not overflowing right edge).
5. Click stop button → banner unmounts.

```js
const banner = document.querySelector('[data-testid="impersonation-banner"]');
const stopBtn = document.querySelector('[data-testid="impersonation-stop-btn"]');
const stopRect = stopBtn.getBoundingClientRect();
console.assert(stopRect.right <= window.innerWidth, 'M10: stop button overflowing right edge');
console.assert(stopRect.left >= 0, 'M10: stop button overflowing left edge');
```

---

## Known issues (Session 47 supplement)

| # | Bug | Severity | PR |
|---|---|---|---|
| BUG #1 | `target_email` empty on `IMPERSONATION_BLOCKED` audit row when guard fires BEFORE user-service resolution (`SELF_IMPERSONATION_FORBIDDEN`). Compliance audit trails lose target identity context. | Medium | TODO |
| BUG #3 | FE `ERROR_CODE_MESSAGES` map doesn't cover `VALIDATION_ERROR` shape (uses `fieldErrors[]` not `errorCode`). User sees generic error on reason validation failure. | Medium | TODO |
| BUG #4 | `GET /api/v1/impersonation/sessions?status=ACTIVE` returned 500 `INTERNAL_ERROR` (no `@GetMapping` on root path, falling through `handleGeneric`). **FIXED** by adding `@ExceptionHandler(HttpRequestMethodNotSupportedException.class)` → 405 `METHOD_NOT_ALLOWED` in this PR. | Low (FE didn't hit it; was operator-side discovery) | This PR |

## Design notes

- **NOTE #1**: Impersonation TTL is hardcoded to **60 minutes**, NOT derived from target's `sessionTimeoutMinutes`. mcp-tester (60min config) and testuser (15min config) both produced 60min impersonation sessions. Intentional?: yes — impersonation has own lifecycle separate from regular user session timeouts.
- **NOTE #2**: Raw JS `POST /sessions` creates the DB session but the **FE banner/identity swap does NOT happen** until the FE orchestration runs (PR #411 `impersonation-orchestration.ts` via UI button). Direct API consumers need to call additional FE state refresh OR navigate to refresh authz state. This is a documented limitation — UI button is the canonical entry point.
- **NOTE #3**: Concurrent session policy is **force-single** per admin: 409 `ACTIVE_IMPERSONATION_EXISTS`. Codex `019e1dc5` recommendation confirmed already implemented in BE `ImpersonationSessionService`.

## Related runbooks

- [RB-kc-subject-backfill.md](RB-kc-subject-backfill.md) — V16 migration backfill for legacy users without `kc_subject`.
- Operator action for prod: KC `userId` attribute set on admin users (PR #527 covered persona-only; admin@-style needs separate backfill).
