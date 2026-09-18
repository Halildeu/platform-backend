# Native push implementation — review and activation gates

Tracks platform-mobile#9; meeting-event production is separately tracked in platform-backend#412.

## Implemented, disabled by default

- Authenticated owner/organization scoped registration, rotation, removal by registration ID or installation ID (lost-response recovery).
- AES-256-GCM device token storage; exact application/provider/environment allowlist. Concurrent registration/removal uses the same installation lock.
- FCM HTTP v1 and APNs HTTP/2, service-account access-token refresh and cached ES256 APNs authentication. Secret files are read only when sender-enabled is true.
- Existing push planner fans out to native devices as well as browser subscriptions. Browser adapter remains unchanged. Native routing is applied to initial dispatch and retry.
- Only meeting.summary.ready, meeting.action.assigned and meeting.transcript.ready with a valid payload.meetingId produce native targets. Producer must select authorized recipients; this transport does not infer meeting membership.
- Existing intent/outbox deduplication, eligibility, retry attempt limits and backoff are reused. Retry-After may extend backoff, bounded to seven days; 429 has a minimum 60 seconds.
- Explicit unregistered responses remove only the attempted token hash, preserving newer rotations. Erasure removes owner-scoped native registrations.
- Provider acceptance uses the existing push transport-terminal DELIVERED state, not proof of phone receipt. It does not enter the SMS delivery-receipt workflow.
- Full response deadline and bounded response size prevent stalled provider bodies from occupying a worker indefinitely.

## Configuration

Assignment producer: `meeting.notify.native-push-enabled=true` adds native PUSH
to the existing assignee inbox intent for `meeting.action.assigned` only. It
requires the existing meeting notification sink to be enabled and authenticated.
The default is false. `payload.pushAudience=native` prevents browser fan-out while
preserving native planning, eligibility and retries; absent markers preserve the
existing combined push behavior. The payload contains the canonical meeting ID,
not action text. Occurrence-scoped intent/idempotency keys remain unchanged.
Enabling this flag does not replay already published events.

This producer connection does not implement summary/transcript participant
resolution. Those events must resolve authorized recipients before creating
native intents; do not broadcast to organization members or infer recipients
from spoken names.

Both notify.native-push.registry-enabled and notify.native-push.sender-enabled default off.
Encryption-key is a base64 32-byte institution-managed secret. Do not rotate it without migrating existing ciphertext.
Allowed-scopes is a comma-separated exact applicationId/provider/environment list.
Providers is a list under notify.native-push with application-id, provider, environment, credentials-file and:
- FCM: project-id, matching the service account's project.
- APNS: key-id, team-id, sandbox. Sandbox must match signing entitlement; business TEST does not imply sandbox.
Never commit credentials or put them in the APK. No configuration was activated by this change.

## Mobile contract

POST /api/v1/notify/native-push/registrations: installationId, applicationId, provider, environment, token.
DELETE /api/v1/notify/native-push/registrations/installations/{installationId}: applicationId, provider, environment.
Both require bearer JWT and matching X-Org-Id/X-Subscriber-Id. DELETE is owner scoped and idempotent.
Mobile stores a token-free cancellation receipt before enrollment and serializes rotation/logout.
An offline logout leaves cleanup pending and blocks another account from taking over that receipt. The previous account must reauthenticate to finish cleanup. This is not server-side revocation while offline.

## Still required before activation/closure

- Review of the combined change and exact-head PostgreSQL CI; local Docker is unavailable.
- Verify actual meeting-event producers submit authorized recipients/topic/template/push intent to this service (#412); do not claim event-to-phone acceptance from adapter tests.
- Register TEST FCM/APNs credentials, Android Firebase package config and iOS signing entitlements; mobile extra.nativePush requires enabled, environment TEST and orgId.
- Real Android/iOS permission, token rotation, account-switch/offline-cleanup, tap routing and delivery tests. No provider call or phone delivery has been performed.
- Operational orphan-registration recovery/expiry and encryption-key migration procedure.

Local validation on 2026-09-17: 55 focused unit/planner tests passed (27 original/fanout tests plus provider/adapter/cipher/auth cases; see Surefire reports for exact per-class counts). PostgreSQL tests remain a separate CI gate. No main merge or deployment.
