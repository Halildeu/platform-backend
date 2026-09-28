# Exact-session transcript source status

This internal read explains the transcript service's persisted source state when
a saved analysis result is absent. It does not trigger work, return speech content,
issue an analysis capability or establish an AI worker/result-delivery state.
The public meeting endpoint and mobile presentation are separate integration work.

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
