-- V4 — meeting analysis event outbox (Faz 24, platform-ai#244 BE-1d)
--
-- BE-1c made analysis-result ingestion atomic: one transaction writes the
-- meeting_analysis_runs row plus every AI child (decisions + actions). BE-1d
-- adds the TRANSACTIONAL OUTBOX so the two persistence-blocked domain events of
-- platform-backend#412 can finally be emitted:
--   * meeting.summary.ready    — the analysis result is durably stored
--   * meeting.action.assigned  — an AI-extracted action item has a real assignee
--
-- WHY AN OUTBOX (Codex ai#244 + #412 acceptance):
--   The event MUST NOT be published from inside the ingestion transaction — a
--   crash/rollback after an in-transaction publish would emit an event for a run
--   that never committed (a phantom "your summary is ready" for data that does
--   not exist). Instead the SAME transaction that writes the run + children also
--   writes an outbox row; a SEPARATE poller reads only COMMITTED outbox rows and
--   hands them to a publisher. Commit-after-emit is therefore structural, not a
--   convention: an uncommitted outbox row is invisible to the poller's snapshot.
--
--   * Atomicity: run + children + outbox row all commit, or all roll back.
--   * Exactly-once effect: event_key is UNIQUE (deterministic per run+type+ordinal),
--     so a retried ingestion (or a raced insert) can never create a duplicate row,
--     and a consumer that de-dups on event_key applies each side-effect once.
--   * Attribution guard: an action.assigned row is written ONLY for an action whose
--     assignee is a real, non-blank subject. A NULL assignee (the LLM extracted an
--     action but attributed it to nobody) produces NO event — enforced by the
--     writer, proven by test; the DB does not manufacture the row either.
--
-- This mirrors the notification-orchestrator outbox (V2/V3): a lease + claim_token
-- + FOR UPDATE SKIP LOCKED claim gives multi-pod-safe, crash-recoverable delivery.
--
-- Tenancy follows the V1/V3 org_id compat pattern: tenant_id NOT NULL + nullable
-- org_id back-filled by the shared BEFORE INSERT/UPDATE trigger, with the
-- org_id = tenant_id CHECK invariant.

-- ============================================================
-- Table: meeting_event_outbox
-- ============================================================

CREATE TABLE meeting_event_outbox (
    id                 UUID          NOT NULL,

    -- Domain event type. Pinned to the two #412 events so a bad writer cannot
    -- smuggle an arbitrary type past the poller/consumer contract.
    event_type         VARCHAR(64)   NOT NULL,

    -- The aggregate that produced the event: the analysis_run_id. Kept together
    -- with tenant_id + meeting_id so the composite FK below binds the outbox row
    -- to exactly the run (in the same tenant AND meeting) that it describes.
    aggregate_id       UUID          NOT NULL,
    meeting_id         UUID          NOT NULL,
    tenant_id          UUID          NOT NULL,
    org_id             UUID,

    -- Thin event: identifiers + minimal metadata (counts, grounding status,
    -- ordinal, assignee, due). Deliberately NOT the summary/transcript text — the
    -- consumer fetches the canonical result from meeting-service. Assignee subject
    -- lives here (same trust boundary as meeting_actions.assignee_subject); the
    -- LOGGING publisher never logs it (redaction guard).
    payload            JSONB         NOT NULL,

    -- Deterministic idempotency key: `<analysis_run_id>|<event_type>` for a summary
    -- and `<analysis_run_id>|meeting.action.assigned|<ordinal>` for an action. UNIQUE
    -- (below) makes a duplicate outbox row structurally impossible — the exactly-once
    -- foundation. VARCHAR(200) fits UUID(36)+type(<=28)+ordinal comfortably.
    event_key          VARCHAR(200)  NOT NULL,

    -- Lifecycle: PENDING -> CLAIMED -> PUBLISHED (success terminal) or DEAD
    -- (failure terminal after max attempts). A stale CLAIMED lease is recovered
    -- back to PENDING by the poller (crash recovery).
    status             VARCHAR(16)   NOT NULL DEFAULT 'PENDING',

    -- Per-cycle claim isolation (mirrors notify): the poller stamps a fresh
    -- claim_token on the rows it claimed this cycle, then fetches exactly those.
    claim_token        UUID,
    processing_owner   VARCHAR(128),
    claimed_at         TIMESTAMPTZ,
    lease_expires_at   TIMESTAMPTZ,

    attempts           INTEGER       NOT NULL DEFAULT 0,
    -- Safe operational telemetry only — an exception CLASS name, never payload text.
    last_error         VARCHAR(500),

    created_at         TIMESTAMPTZ   NOT NULL,
    published_at       TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ   NOT NULL,
    version            BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pk_meeting_event_outbox PRIMARY KEY (id),
    CONSTRAINT meeting_event_outbox_org_id_match
        CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT meeting_event_outbox_event_type_known
        CHECK (event_type IN ('meeting.summary.ready', 'meeting.action.assigned')),
    CONSTRAINT meeting_event_outbox_status_known
        CHECK (status IN ('PENDING', 'CLAIMED', 'PUBLISHED', 'DEAD')),
    CONSTRAINT meeting_event_outbox_attempts_non_negative
        CHECK (attempts >= 0),

    -- Composite tenant+meeting FK to the producing run (mirrors V3's child FKs):
    -- an outbox row can only bind to a run in its own tenant AND meeting, and it
    -- cascade-deletes when the run is purged (KVKK retention) — a purged run never
    -- leaves a still-deliverable "summary ready" event behind.
    CONSTRAINT fk_meeting_event_outbox_run
        FOREIGN KEY (aggregate_id, tenant_id, meeting_id)
        REFERENCES meeting_analysis_runs(analysis_run_id, tenant_id, meeting_id) ON DELETE CASCADE
);

-- Exactly-once: one outbox row per (run, event_type, ordinal). A retried ingestion
-- collides here instead of duplicating the event.
CREATE UNIQUE INDEX uq_meeting_event_outbox_event_key
    ON meeting_event_outbox(event_key);

-- Claim path: PENDING rows in insertion order. Partial index keeps it tight.
CREATE INDEX idx_meeting_event_outbox_pending
    ON meeting_event_outbox(created_at, id)
    WHERE status = 'PENDING';

-- Stale-lease recovery path: CLAIMED rows whose lease expired.
CREATE INDEX idx_meeting_event_outbox_claimed_lease
    ON meeting_event_outbox(lease_expires_at)
    WHERE status = 'CLAIMED';

-- This-cycle fetch after an atomic claim.
CREATE INDEX idx_meeting_event_outbox_claim_token
    ON meeting_event_outbox(claim_token)
    WHERE claim_token IS NOT NULL;

CREATE INDEX idx_meeting_event_outbox_aggregate ON meeting_event_outbox(aggregate_id);
CREATE INDEX idx_meeting_event_outbox_org_id ON meeting_event_outbox(org_id);

DROP TRIGGER IF EXISTS meeting_event_outbox_org_id_compat ON meeting_event_outbox;
CREATE TRIGGER meeting_event_outbox_org_id_compat
    BEFORE INSERT OR UPDATE ON meeting_event_outbox
    FOR EACH ROW EXECUTE FUNCTION meeting_org_id_compat_fill();

COMMENT ON TABLE meeting_event_outbox IS
    'Faz 24 platform-ai#244 BE-1d. Transactional outbox for meeting analysis domain events (#412 meeting.summary.ready + meeting.action.assigned). Written in the SAME transaction as the analysis run + children (atomic); a separate poller publishes only committed rows (commit-after-emit). event_key UNIQUE = exactly-once.';
COMMENT ON COLUMN meeting_event_outbox.event_key IS
    'Deterministic idempotency key: <run>|meeting.summary.ready or <run>|meeting.action.assigned|<ordinal>. UNIQUE — a retried/raced ingestion cannot duplicate an event.';
COMMENT ON COLUMN meeting_event_outbox.status IS
    'PENDING -> CLAIMED -> PUBLISHED | DEAD. A stale CLAIMED lease (lease_expires_at <= now) is recovered to PENDING by the poller.';
COMMENT ON COLUMN meeting_event_outbox.payload IS
    'Thin event body: identifiers + minimal metadata. NEVER the summary/transcript text — consumers fetch the canonical result from meeting-service.';
