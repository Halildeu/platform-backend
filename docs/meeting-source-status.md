# Exact-session transcript source status

This internal read explains the transcript service's persisted source state when
a saved analysis result is absent. It does not trigger work, return speech content,
issue an analysis capability or establish an AI worker/result-delivery state.
The public meeting endpoint combines it with a separate saved-result observation;
mobile presentation and deployment remain separate integration work.

## Contract

`GET /api/v1/internal/tenants/{tenantId}/meetings/{meetingId}/sessions/{sessionId}/source-status`

- `sessionId` is the canonical meeting-session UUID, never the gateway `SES-...` alias.
- Requires the existing trusted service JWT with `transcript:canonical:read` and
  an `X-Tenant-Id` exactly equal to the path tenant. Other service permissions do not suffice.
- No analysis-run/spec headers are needed before a finalization exists.
- Successful responses have `Cache-Control: no-store`, `Pragma: no-cache`, and no
  analysis-capability headers. Metadata-only STATUS access audit must commit with the read.

```json
{
  "tenantId": "11111111-1111-4111-8111-111111111111",
  "meetingId": "22222222-2222-4222-8222-222222222222",
  "sessionId": "33333333-3333-4333-8333-333333333333",
  "state": "FAILED",
  "cycleVersion": 2,
  "observationRevision": 14,
  "observedAt": "2026-09-28T12:00:00Z",
  "failureCode": "NO_VALID_SEGMENTS_BEFORE_DEADLINE",
  "recordingOutcome": "INCOMPLETE",
  "recordingIncompleteReason": "CLOSURE_UNCONFIRMED",
  "finalizedOccurrence": null
}
```

| State | Meaning at observation time |
| --- | --- |
| UNKNOWN | No resolved association, or its matching immutable snapshot is absent. Neither processing nor retention expiry is inferred. |
| AWAITING_CLOSURE | Transcript service has not observed authoritative closure, including editorial snapshots made before closure. Does not mean the microphone is currently open. |
| QUIESCING | The current source-preparation cycle is persisted as quiescing. Does not prove a worker is running now. |
| FINALIZED | The matching immutable occurrence is retained at the read. Does not prove complete capture, AI execution, result storage, or readable/integrity-verified transcript content. |
| FAILED | The observed source-preparation cycle failed. Distinct late content can open a newer cycle and clear this failure. |

`failureCode` is null except for an allowlisted persisted FAILED reason:
`NO_VALID_SEGMENTS_BEFORE_DEADLINE` or `INVALID_CANONICAL_SEGMENT`. Arbitrary stored
error strings are never exposed. Unknown reason codes remain null, not a guessed cause.

`cycleVersion` and `observationRevision` are null only when the association is absent.
The revision is that association row's version, not an ETag for the whole pipeline:
retention can remove a snapshot without changing it. `observedAt` is the read time,
not the failure/closure time. General `updatedAt` is intentionally not presented as a transition timestamp.

When FINALIZED, `finalizedOccurrence` contains `finalizationVersion`, producer-owned
`analysisRunId`, `finalizedAt`, and its own `recordingOutcome`/`recordingIncompleteReason`.
Those closure fields belong to that immutable occurrence. V15 intentionally preserved
historical snapshot UNKNOWN values even when association closure was backfilled as
FINISHED; consumers must not replace the nested provenance with the outer provenance.
Before authoritative closure, editorial occurrences are omitted because later content
may already exist beyond that snapshot. No older finalization is substituted for a missing current one.

## Privacy and concurrency

The write-capable read transaction locks the canonical erasure fence, checks the
tombstone, then locks the resolved association. Its matching snapshot uses a scoped
pessimistic read lock so retention deletion cannot remove it before the observation
and STATUS audit commit. This is an observation, not a guarantee of later availability.

- Completed erasure: `410 TRANSCRIPT_ERASED` before looking for the association.
- READY or HELD erasure: `423 TRANSCRIPT_ERASURE_PENDING`. An exact held content
  read's exception does not authorize mutable session-wide status disclosure.
- Tenant mismatch: 403. Missing tenant header or malformed UUID: 400.
- Invalid current occurrence metadata: `409 SOURCE_STATUS_INTEGRITY_MISMATCH`.
- Missing resolved association: 200 UNKNOWN. It may represent an unresolved source
  alias; this endpoint does not search unrelated aliases or sessions to guess its status.
- Database/audit failure is not converted into UNKNOWN or a successful response.

STATUS uses the existing content-free transcript access audit table and retention
policy, with meeting/session IDs and the authenticated service subject. It has no
transcript text, segment count, source alias, error detail, or fabricated analysis-run ID.

## Public exact-session observation

`GET /api/v1/admin/meetings/{meetingId}/sessions/{sessionId}/processing-status`

This user-token endpoint requires meeting scope, the module viewer gate, and the
same stable-owner/blocked-principal policy used by canonical transcript readback.
The canonical session must belong to the requested meeting and effective org.
There is no latest-meeting or gateway-alias fallback. Successful responses are
`no-store` and `Pragma: no-cache`.

The response contains `meetingId`, `sessionId`, `source` (the internal observation
above, including its own `observedAt`), and `savedResult`:

```json
{
  "state": "AVAILABLE",
  "observedAt": "2026-09-28T12:00:02Z",
  "analysisRunId": "44444444-4444-4444-8444-444444444444",
  "finalizationVersion": 1,
  "finalizedAt": "2026-09-28T11:59:00Z",
  "recordingOutcome": "INCOMPLETE",
  "recordingIncompleteReason": "CLOSURE_UNCONFIRMED",
  "matchesCurrentSourceOccurrence": false
}
```

`AVAILABLE` establishes only that the exact session's selected result ledger row
was present at observation time, not that its contents are correct or capture was
complete. `NOT_FOUND` has null run/version/finalizedAt/provenance/match fields and
does not mean an AI worker is running, failed, queued, or finished.

`matchesCurrentSourceOccurrence` is three-valued:

- `true`: run ID, version, finalized time and immutable occurrence closure match.
- `false`: the saved result version is older than the observed source cycle.
- `null`: comparison is unestablished, including a legacy result, absent source
  association, or a result that may have arrived after the source observation.

A same-run or same-version contradiction returns 409. Comparison uses nested
immutable occurrence provenance, never the association's backfilled closure.
Two database reads are not an atomic pipeline snapshot; retain their separate
observation times. No source revision is advertised as a whole-pipeline ETag.

Owner and source network calls execute outside the locked result transaction.
Both before and after the source call, a local erasure tombstone is checked before
session existence: PENDING/ACTIVE/HELD return 423, COMPLETE returns 410 even when
the session has already been deleted. After the remote call, the transaction
locks the parent meeting (shared with erasure and result ingestion), then checks
erasure/session and reads the exact result with a pessimistic read lock against
retention. A metadata-only `SESSION_PROCESSING_STATUS_READ` audit commits with
this observation; audit failure denies the read.

Meeting migration V20 permits session-status audits before any result exists.
Status audits require session ID and null run ID. Existing result/transcript
audit types retain mandatory run ID and null session ID. No foreign key couples
audit evidence to session deletion; existing retention and count constraints
remain in force.

The separate HTTP client reuses canonical-read service credentials and bounded
timeouts, retries 401 once, validates exact scope and enum/provenance combinations,
and rejects capability-bearing responses. An upstream 404/403/5xx or disabled read
is `503 SOURCE_STATUS_UNAVAILABLE`, never inferred retention or saved-result absence.
Upstream erasure remains 410/423; malformed/conflicting metadata returns 409.

## Integration and acceptance

The meeting service must separately authorize module access plus meeting owner/blocked
rules, validate the exact canonical session, then read this status. It must recheck
local erasure/result state under its own fence after the remote observation. The two
databases are not an atomic snapshot. A saved result must be matched by the exact
producer run/version; an earlier result may be shown only as an earlier occurrence.
Do not reuse the existing snapshot client's 404-to-retention mapping for this route.
An unavailable/older deployment of this route is an unavailable observation.

The source-status contract alone does not expose AI queued/running/failed/delivery
states. Those require an authoritative AI inbox/outbox observation for the exact
occurrence. It neither recovers the user's missing result nor fixes action semantics.

Regression coverage includes authority/header denial, no capability emission,
unknown/audited observations, bounded failure reasons, late-cycle recovery,
editorial-before-closure, historical immutable provenance, erasure precedence,
retention and erasure races, exact scope, and audit failure propagation. The real
PostgreSQL suite checks actual lock-timeout SQLSTATE 55P03 and subsequent visibility;
unit mocks are not a substitute for that suite.
