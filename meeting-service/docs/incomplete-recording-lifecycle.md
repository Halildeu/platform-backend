# Incomplete recording lifecycle contract

This source proposal pairs with platform-mobile ADR0019 and issue #7. Deploy the
schema, compatible transcript consumer and result-completeness contracts before
emitting the new incomplete event in a rollout. No deployment or physical-device
completion is claimed here. This proposal is not yet deployable on its own: the
previous transcript consumer ACKs unknown event types, so producer-first deployment
would discard the new occurrence from that consumer group.

## Canonical routes (authenticated recording access)

- GET `/api/v1/admin/meetings/{meetingId}/recording-lifecycle/{externalSessionId}`
  reads only a session created by the current subject; missing/foreign is 404.
- PUT `/api/v1/admin/meetings/{meetingId}/recording-lifecycle/abandon` accepts
  `{externalSessionId, startedAt, endedAt}`. Exact existing owner and start required.
  First abandon stores `recordingIncomplete=true`, `transcriptStatus=FAILED`, fixed
  endedAt. Identical retry returns the stored result. Different times or previously
  finished session return 409. No `meeting.recording.finished` outbox record.
  Instead, the same transaction stores one `meeting.recording.incomplete` outbox
  event: canonical recording scope, revision 1, stable session-based key,
  `closedAt=generatedAt=stored endedAt`, and bounded `CLOSURE_UNCONFIRMED` reason.
  Exact authorized retries also repair a pre-event incomplete closure's missing
  outbox row. Existing rows must match the stored scope and exact payload; their
  publication/dead-letter state is never reset. Conflicting or erased recordings
  cannot create/repair events.
  Times finer than PostgreSQL microseconds are rejected by request validation,
  preventing an apparently exact retry from changing identity after database reload.
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

## Transcript closure and immutable snapshots

The transcript consumer accepts distinct finished/incomplete events, with strict
scope, key, payload shape and bounded reason validation. Incomplete close time must
equal generatedAt and be representable exactly in database microseconds. The inbox
stores and verifies the actual event type; processing and the association transition
share the existing erasure/source locks and transaction. Conflicting closure kinds
or times roll back rather than promote incomplete input to successful capture.

Each association and immutable snapshot carries `RecordingOutcome`:
`UNKNOWN`, `FINISHED`, or `INCOMPLETE`. Only INCOMPLETE permits a reason, and it
requires `CLOSURE_UNCONFIRMED`. FINISHED denotes an observed canonical finished
event; it is not independent proof that every sound sample was captured. V15
backfills association rows with a pre-existing finished timestamp to FINISHED;
historical snapshots remain UNKNOWN. Both editorial and automatic snapshots copy
their outcome at creation; subsequent changes cannot rewrite that provenance.

First closure after an editorial snapshot starts another version even if the text
is unchanged. Replaying the earlier version does not retire the newer cycle. Late
content before any closure updates the content marker but does not start invalid
quiescence. For legacy known closures missing an observation timestamp, late content
uses its current observation time when reopening. Duplicate closure never extends
deadlines. Existing 6-minute minimum, 1-minute quiescence and 15-minute maximum
policies are preserved. Empty retained content still produces a failed event.

## Remaining integration before release

The producer/consumer/snapshot changes are prerequisites, not a complete saved-result
implementation. Snapshot outcome still must be bound to authoritative
analysis capabilities, then propagated to saved results and exports. Successful
analysis must not change an incomplete recording to a successful capture; the V17
FAILED invariant remains intentional. Empty retained content needs an explicit,
readable failure status, not an indefinite ambiguous 404. Mobile must preserve
closure retries and visibly label analysis based on incomplete input.

Consumer compatibility must be deployed before producer emission. Existing exact
retry repairs alone do not redrive an already-published event that an old consumer
ACKed as unknown. Such a rollout requires a separately reviewed durable redrive.
V15 also requires coordinated transcript deployment: stop and drain old transcript
closure consumers and finalization workers before migrating/starting the new writer.
An old writer does not populate the new outcome and can violate the association
constraint or produce an unqualified snapshot. Do not run mixed writers against
this schema. Canonical read/capability, strict AI readers, result storage and mobile
contracts must be integrated and verified before enabling incomplete emission.
The user's actual truncated report does not prove its terminal server-side state.
