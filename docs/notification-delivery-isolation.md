# Notification delivery isolation

Tracked by platform-mobile#9, backend PR1176 and platform-k8s-gitops#3793.

Domain event publication and notification HTTP delivery use independent persistent state. A successful fenced domain PUBLISHED transition and a unique notification job insertion commit in the same local transaction. A lost fence enqueues nothing. Failed handoff rolls back the transition; ordinary at-least-once Redis crash recovery remains unchanged.

A separate worker locks one job with PostgreSQL FOR UPDATE SKIP LOCKED during delivery. It has its own executor, retry counter (default 20), 30-second retry delay and terminal DEAD state. HTTP failure cannot republish a domain event or consume its retry budget. A process failure rolls back the job transaction; existing per-recipient intent idempotency keys protect retries after uncertain HTTP outcomes. This is not an exactly-once network delivery guarantee.

The queue stores only a source-row reference and delivery status; source deletion cascades to the job. No transcript or additional recipient snapshot is copied. Each attempt resolves authorized recipients again. Native summary delivery disabled by configuration pauses existing summary jobs without consuming attempts; assignment delivery continues. Disabled transcript notifications do not run their worker. Activation does not backfill already-published events from before this change.

Migrations: meeting V16 and transcript V15 are additive. Preserve queue tables and data when rolling back application binaries. A rollback to the previous binary resumes its previous coupled delivery behavior and does not drain the new queue. Before rollout, inspect pending/DEAD domain events; this change does not automatically repair historic DEAD rows.

Verification: focused unit tests cover no HTTP on the domain publisher, lost-fence exclusion, isolated retry and transaction requirement. PostgreSQL regression tests cover atomic handoff rollback, unique enqueue, concurrent lock exclusion, process-loss rollback, backoff, independent counters and source-erasure cascade. Live TEST rollout and physical FCM/APNs delivery are separate acceptance gates.
