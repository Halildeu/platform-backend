# Faz 24 Retention Cleanup Jobs

This document records the backend source-side mechanics added for
`platform-ai#156` DB retention blockers. It does not close the go-live blocker
by itself because VERBIS operator status and live runtime evidence remain
separate acceptance inputs.

## Layers

| Gate layer | Service | Table(s) | Retention | Cleanup |
|---|---|---|---:|---|
| `db.transcript-records` | `transcript-service` | `transcript_segments` | 365 days | `TranscriptRetentionCleanupService` |
| `db.kvkk-access-log` | `transcript-service` | `transcript_access_audit` | 730 days | `TranscriptRetentionCleanupService` |
| `db.meeting-intelligence` | `meeting-service` | `meeting_actions`, `meeting_decisions` | 730 days | `MeetingRetentionCleanupService` |

## Destruction Audit

Both services write metadata-only destruction audit rows in the same transaction
as bounded id-only deletion of expired records:

- `transcript_retention_destruction_audit`
- `meeting_retention_destruction_audit`

Allowed payload values are `metadata-only`, `id-only`, and `transcript-free`.
The audit rows intentionally contain only layer id, cutoff, counts, job id, and
execution time. They do not store transcript text, action/decision content,
participants, assignees, search terms, prompts, model responses, audio paths, or
tokens.

Cleanup selects and deletes only row ids in bounded batches. The default batch
size is `1000` and the default per-run batch limit is `100`, so a first
deployment backlog cannot turn into an unbounded transaction. Operators can
tune `TRANSCRIPT_RETENTION_CLEANUP_BATCH_SIZE`,
`TRANSCRIPT_RETENTION_CLEANUP_MAX_BATCHES_PER_RUN`,
`MEETING_RETENTION_CLEANUP_BATCH_SIZE`, and
`MEETING_RETENTION_CLEANUP_MAX_BATCHES_PER_RUN`.

## Runtime Boundary

The scheduled jobs are source-side mechanics for #156 evidence. Passing unit/JPA
tests proves the cleanup selectors and audit payload shape, not live production
deletion. #156 can pass only after deployment evidence, VERBIS recorded or
exempt-confirmed status, MinIO lifecycle evidence, and metadata-only DB cleanup
evidence are all attached to the platform-ai retention gate.
