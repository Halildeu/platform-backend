# RB-kc-subject-backfill — Keycloak subject backfill for users.kc_subject

> Status: **Operator action required** after platform-backend#159 merge.
> Codex thread `019e1bed` REVISE-1 absorb.

## Why this exists

`user-service` V16 introduces `users.kc_subject` so the impersonation
start path can resolve the target user's Keycloak UUID server-side
(removing the need for the admin UI to ask operators for a KC UUID).
For brand-new users the provisioning flow writes `kc_subject` at create
time. For users that pre-date V16 the column starts `NULL` and the
impersonation start path returns **`422 TARGET_SUBJECT_UNRESOLVABLE`**
until backfilled.

This runbook restores the mapping by joining the platform `users` table
on `email` with the Keycloak `user_entity` rows in the realm those
users live in. It is idempotent — re-running it is safe.

## Diagnosis

UI symptom:
> "Hedef kullanıcının Keycloak eşlemesi eksik (kc_subject backfill
> bekleniyor). Operatöre bildirin."

Backend `auth-service` log (`ImpersonationController.startSession`):
```
errorCode=TARGET_SUBJECT_UNRESOLVABLE
message=Hedef kullanıcı için Keycloak subject çözümlenemedi
```

`permission_db.permission_audit_events`:
```
event_type=IMPERSONATION_BLOCKED
errorCode=TARGET_SUBJECT_UNRESOLVABLE
```

DB check:
```sql
-- users with no kc_subject yet (target backfill candidates)
SELECT id, email FROM users WHERE kc_subject IS NULL ORDER BY id;
```

## Backfill procedure

### Step 1 — Export KC user_entity rows for the realm

Run inside the Keycloak DB container:

```bash
# Replace `platform-test` with the prod realm (`serban`) when running on prod.
docker exec platform-pg-test psql -U postgres -d keycloak -A -F$'\t' -t -c "
  SELECT u.id, lower(u.email)
  FROM user_entity u
  JOIN realm r ON r.id = u.realm_id
  WHERE r.name = 'platform-test'
    AND u.email IS NOT NULL
" > /tmp/kc_subjects.tsv

wc -l /tmp/kc_subjects.tsv
```

`kc_subjects.tsv` is `<kc_uuid>\t<lower(email)>` pairs.

### Step 2 — Apply to users_db (test)

```bash
docker exec -i platform-pg-test psql -U postgres -d users_db <<'SQL'
BEGIN;

CREATE TEMP TABLE _kc_subjects (kc_id text, email text);
\copy _kc_subjects FROM '/tmp/kc_subjects.tsv' WITH (FORMAT csv, DELIMITER E'\t');

-- Idempotent: only fill rows that are still NULL.
UPDATE users u
SET    kc_subject = k.kc_id
FROM   _kc_subjects k
WHERE  lower(u.email) = k.email
  AND  u.kc_subject IS NULL;

-- Verify: count of users still missing kc_subject + sample.
SELECT count(*) AS still_null FROM users WHERE kc_subject IS NULL;

COMMIT;
SQL
```

Copy `/tmp/kc_subjects.tsv` from the KC container to the users_db
container first if they are on different hosts.

### Step 3 — Cache invalidation

KC caches user attributes in-memory; the controller resolves
`users.kc_subject` from user-service (not directly from KC), so users
that authenticate **after** the backfill see the fix immediately. But
admin sessions that already hold a JWT without an updated `userId`
claim still need a re-login (separate from `kc_subject`; see
`ADMIN_IDENTITY_MISSING` remediation below).

If KC user attributes were changed in parallel (e.g. `userId`):
```bash
# Test only — restarts the KC container and flushes in-memory cache.
docker restart platform-kc-test
```

Prod KC should not be restarted just for cache flush; use
`kcadm.sh logout-all` or invalidate sessions per user via the admin API
instead.

### Step 4 — Smoke

After backfill, send a real impersonation start request from the admin
UI (or curl) and confirm:

- HTTP `201` (not 422)
- DB `impersonation_sessions` row with `impersonator_user_id`,
  `target_user_id`, `status=ACTIVE`
- Audit row `event_type=IMPERSONATION_STARTED`
- Stop button rückgang → `status=STOPPED`, audit
  `event_type=IMPERSONATION_REVOKED`

## ADMIN_IDENTITY_MISSING (related but separate)

This is **not** a `kc_subject` problem. It means the *admin* JWT does
not carry a `userId` claim — i.e. the KC frontend client mapper
`platform-userId` is not active for that admin, OR the admin's KC
attributes lack `userId`. Fix:

1. Confirm the realm's `frontend` client has the `platform-userId`
   mapper (`user.attribute=userId → claim.name=userId`).
2. Set the admin user's `userId` attribute (KC admin REST or DB):
   ```sql
   INSERT INTO user_attribute (id, name, user_id, value)
   VALUES (gen_random_uuid(), 'userId', '<kc_uuid>', '<platform_user_id>')
   ON CONFLICT DO NOTHING;
   ```
3. Force the admin to log out and re-login so the new JWT contains
   the claim. (Token refresh inside an active session may not pick up
   the attribute until the access token expires.)

## Direct DB INSERT — emergency only

Direct DB writes are acceptable for test/emergency operator action.
Steady-state path must go through the user-service create flow (which
writes `kc_subject` at provisioning time) and the Keycloak Admin API
(which keeps KC user attributes consistent). A follow-up reconcile
script (`docs/runbooks/RB-kc-subject-reconcile.md`, TBD) will scan and
report users that are missing `kc_subject` or `userId` attribute drift.

## Prod vs test

- **Test realm `platform-test`**: bypass DB direct writes acceptable.
  Restart KC container if attribute cache flush is needed.
- **Prod realm `serban`**: DB direct writes only as last-resort
  emergency. Prefer:
  1. `kcadm.sh` for KC attribute updates.
  2. Standard provisioning flow re-run for users created post-cutover.
  3. Per-user `users/{id}/logout` via KC Admin REST instead of
     container restart.
