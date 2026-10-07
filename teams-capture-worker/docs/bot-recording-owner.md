# Bot recording consent owner — integration contract

This is the synchronous authority for **Teams live transcription** consent. It does not enable capture,
join a call, authorize ongoing audio dispatch, or replace Microsoft recording-policy/recording-status requirements.
The native host, meeting-service user facade and continuous audio admission fence are separate unfinished integrations.

## Private service surface

`audit-event-consumer-service` exposes the following POST commands only when `AUDIT_BOT_RECORDING_ENABLED=true`:

| Path below `/api/v1/internal/bot-recording` | Command | Result |
| --- | --- | --- |
| `/grant` | `BotRecordingContract.Grant` | Committed intent with a server-generated ID |
| `/lookup` | `Lookup` with stable `OwnerKey` | Stored snapshot, including terminal/expired evidence |
| `/bind` | `Bind` with full freshly revalidated owner and revision 1 | Exactly one immutable worker/call/media binding |
| `/revoke` | `Lookup` with stable `OwnerKey` | Terminal withdrawal; exact retry is idempotent |

Responses carry `Cache-Control: no-store`. Snapshots have `schemaVersion=1`, fixed purpose `TEAMS_LIVE_TRANSCRIPTION`,
and state `GRANTED`, `BOUND` or `REVOKED`. An expired snapshot is readable but cannot bind. Reading an old `BOUND`
snapshot is **not authorization to send audio**. Grant retries do not refresh expiry, undo withdrawal or rebind.

Only an RS256 SERVICE token with the configured issuer, audience `audit-event-consumer-service`,
`sub=client_id=meeting-service`, original signed `iat`/`exp`, and permission `audit:bot-recording:manage` is accepted.
User tokens, calendar scheduling tokens, worker tokens, `azp` substitutes and missing trust configuration cannot admit requests.
Required trust settings: `AUDIT_BOT_RECORDING_SERVICE_ISSUER` and `AUDIT_BOT_RECORDING_SERVICE_JWKS_URI`.
The default is **disabled**. This change does not mint this permission or configure an approved caller in auth-service.
Existing actuator health/info/metrics/prometheus access is retained; unrelated application routes are denied.

The later meeting-service facade must derive all owner fields from authenticated user context, canonical meeting scope
and protected Microsoft identity lookup. Never forward a browser's claimed owner, company, principal or organizer as authority.
It must validate the displayed consent text/version, reserve a stable request key, and forward an expiry no more than 24 hours ahead.
Workers must not receive the manage permission: they request admission through meeting-service, which retrieves stored actor
evidence and freshly checks directory identity, meeting lifecycle and recording rights before invoking `/bind`.
Owner lookup/withdrawal needs stable company + issuer + subject identity, not CAN_RECORD or a still-working Microsoft broker link.
Binding requires the complete fresh owner tuple to match the historical grant; a changed link cannot reuse the grant.

## Persistence and concurrency

V3 adds separate `bot_recording_intent` and append-only `bot_recording_evidence` tables. V1/V2 and all mobile/desktop grant,
withdrawal and session paths are unchanged. Legacy Redis consent events cannot authorize or mutate these bot records.
Every command acquires the existing **numeric company ID** transaction advisory lock before its authoritative reads.
Each state mutation, evidence snapshot and hash-chain audit append share a single transaction. The legacy
`AuditEventPersistenceService.persist(REQUIRES_NEW)` is deliberately not called by this owner.

Evidence stores the exact snapshot JSON and its SHA-256; the same hash is included in the audit event's hashed
`correlation_id`. Database triggers forbid evidence updates/deletes, immutable owner/expiry replacement, established
binding replacement and terminal reopening. JPA table access honors the configured non-public Hibernate schema.

Idempotency is scoped by company, issuer, subject and request key. Material changes using the same key are conflicts.
Bind retries require the exact same worker/call/media tuple and cannot succeed after revocation or expiry.
Concurrent bind/withdrawal is serialized; final withdrawal is terminal. A bind may legitimately commit before a
subsequent withdrawal, so its response must not become a long-lived permission cache.

## Tests and activation boundary

The existing Maven audit-consumer CI job runs the added PostgreSQL suite, including real advisory-lock concurrency,
state/audit/evidence rollback, actual legacy grant/withdrawal isolation, non-public schema and immutable row guards.
Signed-token and HTTP tests cover the exact identity boundary, disabled surface and actual actuator endpoints.
No test uses `disabledWithoutDocker`; PostgreSQL execution is a required result, not inferred from unit tests.

Before activation: finish the user facade, narrow token grant/transport, worker admission and ongoing dispatch/revocation
fence. Define call/media-session uniqueness across intents. Require current consent at queued-frame dispatch; withdrawal
must discard buffered audio, not flush it. Wire the real Windows SDK host and satisfy Microsoft's recording policy.
Publish through TEST GitOps and verify real meeting live/saved behavior. This owner-only change does not close those tasks.
