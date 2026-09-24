# Meeting Intelligence Session Result Read

Tracked by https://github.com/Halildeu/platform-k8s-gitops/issues/3421.
Customer step: reread a prior recording session after a later session has a result.

## Contract

`GET /api/v1/admin/meetings/{meetingId}/intelligence/result` keeps its existing
meeting-wide latest-result behavior when `sessionId` is absent.

`GET /api/v1/admin/meetings/{meetingId}/intelligence/result?sessionId={sessionId}`
returns the latest stored aggregate for that exact session within the visible
meeting and effective organization. Use the stored session identifier (1..64
characters, nonblank); matching is exact, not a search. An empty selector is
invalid, not equivalent to an omitted selector.

- Unknown or foreign session: `404 ANALYSIS_RESULT_NOT_FOUND`, never another
  session's latest result. Unknown or foreign meeting: `404 MEETING_NOT_FOUND`.
- Blank or oversized selector: `400 INVALID_SESSION_ID`, without echoing input.
- Same ordering as the default result: canonical before legacy, then descending
  finalized-at, finalization-version, generated-at, created-at and run-id.
- Response shape, `Cache-Control: no-store`, module viewer authorization,
  effective-org visibility, evidence validation and fail-closed access audit
  are unchanged. Decisions/actions and audit bind to the selected run ID.
- The response's `analysisRunId` identifies the exact source read path
  `/intelligence/results/{analysisRunId}/transcript`; clients must use that run,
  not independently fetch a meeting-wide latest transcript.
- Reads do not change stored analyses, supersession, ingestion replay/stale
  rules, erasure or retention. Existing legacy results remain readable.

## Acceptance Boundary

The backend selector is an enabler, not full customer acceptance. Client session
selection, immutable TEST promotion, normal authorized two-session execution,
source navigation and persistent reopen must be verified on the changed artifact.
PostgreSQL integration and synthetic MVC/unit evidence do not replace those gates.
