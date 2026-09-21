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

Ready-event producers are connected as follows:
- Summary: meeting-service atomically queues delivery with the successful domain
  PUBLISHED transition when both meeting.notify.enabled and
  meeting.notify.native-push-enabled are true; a separate worker invokes
  SummaryReadyNotificationSink.
- Transcript: transcript-service atomically queues delivery with its domain
  PUBLISHED transition when transcript.notify.enabled is true. Its separate
  worker resolves recipients through meeting-service
  (default port 8097), then submits directly to notification-orchestrator.
- MeetingReadyRecipients reads the scoped meeting, enumerates effective owners,
  participants and blocked identities, normalizes aliases through user-service,
  filters disabled/deleted/foreign-company accounts, and subtracts canonical denied
  IDs. Unavailable authorization/directory results fail for retry. Explicit viewers
  are not this audience. The company-derived UUID contract is enforced, with no
  default company substitution.
- A separate occurrence + subscriber idempotency key is used for each ready intent.
  This targets eligible membership at delivery/retry time, not a frozen event-time
  membership snapshot. Partial retries replay unchanged recipients independently.
- Notification delivery failure consumes only the notification queue's retry
  budget; it does not republish the domain event or change its PUBLISHED state.
  Source occurrence and visible erasure requests are checked before ready-event
  handoff. See [delivery isolation](../../docs/notification-delivery-isolation.md)
  for transaction, deletion and rollback semantics. Events already marked
  published while disabled are not replayed by activation.

Approved source configuration: transcript-service client-credentials permissions
meeting:notification:read (audience meeting-service) and notify:intents:system
(audience notification-orchestrator), explicitly pinned by audience. Existing
meeting:session:resolve remains. Both auth-service profiles now contain these narrow
grants, with explicit audience binding; no other client receives the new permission.
No client secret, tenant permission or runtime configuration was changed. Deployed
environment overrides must include the new mint ceiling before activation; source
defaults do not override an existing SECURITY_SERVICE_MINT_ALLOWED_PERMISSIONS value.
The recipient endpoint requires SVC_meeting:notification:read; ordinary/admin user
tokens and other service permissions cannot enumerate recipients.

V31 seeds fixed-copy ready templates in tr-TR/en-US. Combined migrations are covered
by local PostgreSQL integration tests; deployed schema verification and real
provider/device delivery remain deployment gates.

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

- Review of the combined change and exact-head PostgreSQL CI. Local Java 21 /
  PostgreSQL integration evidence is recorded separately in the integration PR;
  it is not a remote CI or deployed-service result.
- Verify actual meeting-event producers submit authorized recipients/topic/template/push intent to this service (#412); do not claim event-to-phone acceptance from adapter tests.
- Register TEST FCM/APNs credentials, Android Firebase package config and iOS signing entitlements; mobile extra.nativePush requires enabled, environment TEST and orgId.
- Real Android/iOS permission, token rotation, account-switch/offline-cleanup, tap routing and delivery tests. No provider call or phone delivery has been performed.
- Operational orphan-registration recovery/expiry and encryption-key migration procedure.

Historical validation on 2026-09-17: 55 focused unit/planner tests passed (27 original/fanout tests plus provider/adapter/cipher/auth cases). At that point PostgreSQL tests were still pending. Later integration evidence is recorded in the combined integration PR. No main merge or deployment is claimed by this document.
