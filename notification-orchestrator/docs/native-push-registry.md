# Native push registry (source preparation)

Tracked by platform-mobile#9 and platform-backend#412.

Disabled by default. `notify.native-push.registry-enabled=true` exposes authenticated
POST `/api/v1/notify/native-push/registrations` and DELETE `/{registrationId}`.
Both use the existing org/subscriber authorization guards, plus an explicit JWT requirement.
`notify.native-push.encryption-key` must contain a base64 encoded 32-byte key from
institutional secret storage. No key is committed. Key rotation/migration is not implemented;
do not change the key while registered tokens remain encrypted with the previous key.
`notify.native-push.allowed-scopes` is a comma-separated list of exact
`applicationId/FCM/TEST` or `applicationId/APNS/PRODUCTION` scopes (both providers
support either business environment). Empty configuration rejects all registrations.

POST: installationId (UUID), applicationId, provider (FCM/APNS), environment
(TEST/PRODUCTION), token. Response: registrationId and status only.
Repeated registration for the same application/provider/environment/installation retains its
ID and updates the encrypted token. Another owner cannot overwrite the registration.
Logout must DELETE using the old authenticated account before account replacement.
Deletion physically removes token material, and is idempotent and scoped to its owner.
An orphaned registration requires a separate verified recovery/expiry design; no takeover
based solely on caller-supplied installation UUID is permitted.

Token values are AES-256-GCM encrypted with random nonces and registration ID as
authenticated associated data. Unique token fingerprints prevent two active installations
from registering the same provider token in the same application/environment.

`NativePushHttpSender` implements FCM HTTP v1 and APNs HTTP/2 transport with injected
short-lived authorization suppliers. It sends generic notification text and only the
event/meeting identifiers, never transcript or summary text. It is not a Spring bean
and is not connected to the queue. APNs sandbox selection must match the signing
entitlement; a TEST business environment does not imply APNs sandbox.
Provider acceptance is distinct from phone delivery. Explicit UNREGISTERED responses
are distinguished from generic 404, configuration errors and retryable failures.
There is no internal automatic retry. Retry-After handling, bounded scheduling,
outbox deduplication and conditional invalid-token cleanup must be provided by integration.

Remaining: credential refresh/injection, delivery planner integration without replacing
webpush, native client registration/logout wiring, erasure/orphan recovery, complete
PostgreSQL verification and real-device acceptance. Keep registry disabled until these
gates are met. A failed offline logout must not silently attach an old registration to
a new account; generic notification text alone does not establish account isolation.
Do not return delivery success based on registration success.

Validation: 19 focused local tests passed on 2026-09-16 (cipher, authorization guards,
scope policy and HTTP transport with mocked responses). Five real PostgreSQL tests
cover rotation, cross-owner rejection, deletion, conflict, rollback and concurrency;
their runtime result is tracked separately. No actual provider request was sent.

Protocol references:
- https://firebase.google.com/docs/cloud-messaging/send/v1-api
- https://firebase.google.com/docs/cloud-messaging/error-codes
- https://developer.apple.com/library/archive/documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/CommunicatingwithAPNs.html
