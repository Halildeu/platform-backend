# Incomplete recording lifecycle contract

This source proposal pairs with platform-mobile ADR0019 and issue #7. Deploy the
schema and both services before the corresponding phone build. No deployment or
physical-device completion is claimed here.

## Canonical routes (authenticated recording access)

- GET `/api/v1/admin/meetings/{meetingId}/recording-lifecycle/{externalSessionId}`
  reads only a session created by the current subject; missing/foreign is 404.
- PUT `/api/v1/admin/meetings/{meetingId}/recording-lifecycle/abandon` accepts
  `{externalSessionId, startedAt, endedAt}`. Exact existing owner and start required.
  First abandon stores `recordingIncomplete=true`, `transcriptStatus=FAILED`, fixed
  endedAt. Identical retry returns the stored result. Different times or previously
  finished session return 409. No `meeting.recording.finished` outbox record.
- Existing normal lifecycle PUT now checks owner and exact start/end for an existing
  session. An incomplete session cannot finish. Conflicting retries return 409 rather
  than echoing a different canonical timestamp. Late old start requests cannot reopen.

Finish and abandon acquire the same meeting FOR UPDATE lock; data and event changes
share the transaction. V17 makes the incomplete marker/start/end immutable and requires
FAILED plus an end date; V16 is reserved by the independent notification proposal.
Meeting COMPLETED denotes no active session; it is not a successful-audio assertion.

Canonical intelligence results carry `incompleteRecordingCount` for all sessions in
the same meeting/organization. Choosing an older successful result does not hide an
incomplete later recording. Count read failures fail the result request, never become 0.

## Gateway route

POST `/api/v1/audio-gateway/sessions/{sessionId}/abandon` uses a mandatory stable
Idempotency-Key and authenticated tenant/user ownership. ABANDONING is set before
cleanup and cancels active live bridges. Audio/EOF/new bridges and finish are rejected.
Dispatcher discard is retried after failures (503, retryable); successful terminal
ack contains sessionId, ABANDONED, finishedAtMs and alreadyFinished replay flag.
Different key or FINISHING/FINISHED is 409. Missing is explicit
`AUDIO_GATEWAY_SESSION_NOT_FOUND` 404. This is separate from generic gateway errors.

Clients must acknowledge canonical abandonment before accepting an absent gateway
session as cleanup success. They must preserve the stable local intent and any audio
until both phases are acknowledged, then erase local audio/key/journal before receipt.

The gateway registry remains in-memory. This contract provides honest incomplete
closure, not durable complete replay; server-owned STT/process receipts remain required.
