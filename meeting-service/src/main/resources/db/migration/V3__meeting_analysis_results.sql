-- V3 — meeting-ai -> meeting-service analysis-result persistence (#244, BE-1)
--
-- Implements the "Verdict A" architecture decision (platform-ai#244):
-- meeting-ai-service is the single server-side analysis producer;
-- meeting-service is the single system-of-record. This migration adds:
--
--   1. meeting_analysis_runs   — one row per accepted analysisRunId; tracks
--      idempotency, staleness (canonical-per-meeting), and supersession.
--   2. meeting_summaries       — the summary text for a run (did not exist
--      before this migration; actions/decisions tables already did).
--   3. meeting_analysis_outbox_events — transactional outbox so summary.ready
--      / action.assigned are emitted only after the persisting transaction
--      commits (exactly-once-effect, backend#412's blocked 2 events).
--
-- meeting_decisions / meeting_actions gain a nullable analysis_run_id FK so
-- automated rows can be told apart from manually-entered ones and atomically
-- superseded on re-analysis, without changing their existing read shape.

-- ============================================================
-- Table: meeting_analysis_runs
-- ============================================================

CREATE TABLE meeting_analysis_runs (
    id                         UUID          NOT NULL,
    meeting_id                 UUID          NOT NULL,
    tenant_id                  UUID          NOT NULL,
    org_id                     UUID,
    analysis_run_id            VARCHAR(255)  NOT NULL,
    transcript_id               VARCHAR(255)  NOT NULL,
    transcript_revision         VARCHAR(255)  NOT NULL,
    analyzer_contract_version   VARCHAR(64)   NOT NULL,
    model_version               VARCHAR(255),
    prompt_version              VARCHAR(255),
    payload_hash                VARCHAR(128)  NOT NULL,
    status                      VARCHAR(32)   NOT NULL DEFAULT 'CANONICAL',
    -- Forward pointer (old row -> new row), set on the row THIS run replaced.
    superseded_by_analysis_run_id VARCHAR(255),
    -- Backward pointer (new row -> old row) — #244 acceptance condition 2
    -- names this field explicitly ("yeni analysisRunId + supersedesAnalysisRunId").
    -- Kept alongside superseded_by_analysis_run_id (not instead of) so a
    -- single row's re-analysis lineage is queryable in either direction
    -- without a self-join.
    supersedes_analysis_run_id  VARCHAR(255),
    generated_at                TIMESTAMPTZ   NOT NULL,
    created_at                  TIMESTAMPTZ   NOT NULL,
    version                     BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_meeting_analysis_runs PRIMARY KEY (id),
    CONSTRAINT meeting_analysis_runs_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT meeting_analysis_runs_status_check CHECK (status IN ('CANONICAL', 'SUPERSEDED')),
    -- Idempotency key #1: the operator-supplied analysisRunId (Idempotency-Key
    -- header) is globally unique.
    CONSTRAINT uq_meeting_analysis_runs_run_id UNIQUE (analysis_run_id),
    -- Idempotency key #2: a retry that regenerates a new analysisRunId for the
    -- exact same (meeting, transcript revision, contract version) is still
    -- recognized as the same logical analysis.
    CONSTRAINT uq_meeting_analysis_runs_identity
        UNIQUE (meeting_id, transcript_revision, analyzer_contract_version),
    CONSTRAINT fk_meeting_analysis_runs_meeting
        FOREIGN KEY (meeting_id, tenant_id) REFERENCES meetings(id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX idx_meeting_analysis_runs_meeting_id ON meeting_analysis_runs(meeting_id);
CREATE INDEX idx_meeting_analysis_runs_org_id ON meeting_analysis_runs(org_id);

-- At most one CANONICAL run per meeting at any time (partial unique index) —
-- this is what "stale-analysis protection" and "re-analysis supersedes the
-- prior canonical" are enforced against.
CREATE UNIQUE INDEX uq_meeting_analysis_runs_one_canonical_per_meeting
    ON meeting_analysis_runs(meeting_id)
    WHERE status = 'CANONICAL';

DROP TRIGGER IF EXISTS meeting_analysis_runs_org_id_compat ON meeting_analysis_runs;
CREATE TRIGGER meeting_analysis_runs_org_id_compat
    BEFORE INSERT OR UPDATE ON meeting_analysis_runs
    FOR EACH ROW EXECUTE FUNCTION meeting_org_id_compat_fill();

-- ============================================================
-- Table: meeting_summaries (1:1 -> meeting_analysis_runs)
-- ============================================================

CREATE TABLE meeting_summaries (
    id                UUID          NOT NULL,
    meeting_id        UUID          NOT NULL,
    tenant_id         UUID          NOT NULL,
    org_id            UUID,
    analysis_run_id   UUID          NOT NULL,
    summary_text      VARCHAR(8000) NOT NULL,
    grounding_status  VARCHAR(32),
    created_at        TIMESTAMPTZ   NOT NULL,
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_meeting_summaries PRIMARY KEY (id),
    CONSTRAINT meeting_summaries_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT uq_meeting_summaries_analysis_run UNIQUE (analysis_run_id),
    CONSTRAINT fk_meeting_summaries_meeting
        FOREIGN KEY (meeting_id, tenant_id) REFERENCES meetings(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_meeting_summaries_analysis_run
        FOREIGN KEY (analysis_run_id) REFERENCES meeting_analysis_runs(id) ON DELETE CASCADE
);

CREATE INDEX idx_meeting_summaries_meeting_id ON meeting_summaries(meeting_id);
CREATE INDEX idx_meeting_summaries_org_id ON meeting_summaries(org_id);

DROP TRIGGER IF EXISTS meeting_summaries_org_id_compat ON meeting_summaries;
CREATE TRIGGER meeting_summaries_org_id_compat
    BEFORE INSERT OR UPDATE ON meeting_summaries
    FOR EACH ROW EXECUTE FUNCTION meeting_org_id_compat_fill();

-- ============================================================
-- Table: meeting_analysis_citations (1:N -> meeting_analysis_runs)
--
-- Grounding provenance for both the summary and the rejected claims'
-- companion evidence — #244 acceptance condition 1 lists citations[] as part
-- of the single atomic ingestion payload.
-- ============================================================

CREATE TABLE meeting_analysis_citations (
    id                  UUID          NOT NULL,
    meeting_id          UUID          NOT NULL,
    tenant_id           UUID          NOT NULL,
    org_id              UUID,
    analysis_run_id     UUID          NOT NULL,
    claim               VARCHAR(4000) NOT NULL,
    source_index        INTEGER,
    source_text         VARCHAR(4000),
    similarity          DOUBLE PRECISION,
    grounded            BOOLEAN,
    status              VARCHAR(64),
    reason              VARCHAR(2000),
    start_sec           DOUBLE PRECISION,
    source_char_start   INTEGER,
    source_char_end     INTEGER,
    source_hash         VARCHAR(128),
    quote_hash          VARCHAR(128),
    created_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_meeting_analysis_citations PRIMARY KEY (id),
    CONSTRAINT meeting_analysis_citations_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT fk_meeting_analysis_citations_meeting
        FOREIGN KEY (meeting_id, tenant_id) REFERENCES meetings(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_meeting_analysis_citations_analysis_run
        FOREIGN KEY (analysis_run_id) REFERENCES meeting_analysis_runs(id) ON DELETE CASCADE
);

CREATE INDEX idx_meeting_analysis_citations_meeting_id ON meeting_analysis_citations(meeting_id);
CREATE INDEX idx_meeting_analysis_citations_analysis_run_id ON meeting_analysis_citations(analysis_run_id);
CREATE INDEX idx_meeting_analysis_citations_org_id ON meeting_analysis_citations(org_id);

DROP TRIGGER IF EXISTS meeting_analysis_citations_org_id_compat ON meeting_analysis_citations;
CREATE TRIGGER meeting_analysis_citations_org_id_compat
    BEFORE INSERT OR UPDATE ON meeting_analysis_citations
    FOR EACH ROW EXECUTE FUNCTION meeting_org_id_compat_fill();

-- ============================================================
-- Table: meeting_analysis_rejected_claims (1:N -> meeting_analysis_runs)
--
-- Claims the analyzer considered but rejected as ungrounded — #244
-- acceptance condition 1 lists rejected_claims[] as part of the payload.
-- Kept durable so a future audit/UI can show "what was NOT included and
-- why", not just the accepted summary/decisions/actions.
-- ============================================================

CREATE TABLE meeting_analysis_rejected_claims (
    id              UUID          NOT NULL,
    meeting_id      UUID          NOT NULL,
    tenant_id       UUID          NOT NULL,
    org_id          UUID,
    analysis_run_id UUID          NOT NULL,
    claim           VARCHAR(4000) NOT NULL,
    kind            VARCHAR(64),
    status          VARCHAR(64),
    reason          VARCHAR(2000),
    similarity      DOUBLE PRECISION,
    created_at      TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_meeting_analysis_rejected_claims PRIMARY KEY (id),
    CONSTRAINT meeting_analysis_rejected_claims_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT fk_meeting_analysis_rejected_claims_meeting
        FOREIGN KEY (meeting_id, tenant_id) REFERENCES meetings(id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_meeting_analysis_rejected_claims_analysis_run
        FOREIGN KEY (analysis_run_id) REFERENCES meeting_analysis_runs(id) ON DELETE CASCADE
);

CREATE INDEX idx_meeting_analysis_rejected_claims_meeting_id ON meeting_analysis_rejected_claims(meeting_id);
CREATE INDEX idx_meeting_analysis_rejected_claims_analysis_run_id ON meeting_analysis_rejected_claims(analysis_run_id);
CREATE INDEX idx_meeting_analysis_rejected_claims_org_id ON meeting_analysis_rejected_claims(org_id);

DROP TRIGGER IF EXISTS meeting_analysis_rejected_claims_org_id_compat ON meeting_analysis_rejected_claims;
CREATE TRIGGER meeting_analysis_rejected_claims_org_id_compat
    BEFORE INSERT OR UPDATE ON meeting_analysis_rejected_claims
    FOR EACH ROW EXECUTE FUNCTION meeting_org_id_compat_fill();

-- ============================================================
-- meeting_decisions / meeting_actions: tag automated rows with their run
-- ============================================================

ALTER TABLE meeting_decisions ADD COLUMN analysis_run_id UUID;
ALTER TABLE meeting_decisions ADD CONSTRAINT fk_meeting_decisions_analysis_run
    FOREIGN KEY (analysis_run_id) REFERENCES meeting_analysis_runs(id) ON DELETE CASCADE;
CREATE INDEX idx_meeting_decisions_analysis_run_id ON meeting_decisions(analysis_run_id);

ALTER TABLE meeting_actions ADD COLUMN analysis_run_id UUID;
ALTER TABLE meeting_actions ADD CONSTRAINT fk_meeting_actions_analysis_run
    FOREIGN KEY (analysis_run_id) REFERENCES meeting_analysis_runs(id) ON DELETE CASCADE;
CREATE INDEX idx_meeting_actions_analysis_run_id ON meeting_actions(analysis_run_id);

-- ============================================================
-- Table: meeting_analysis_outbox_events (transactional outbox)
-- ============================================================

CREATE TABLE meeting_analysis_outbox_events (
    id              UUID          NOT NULL,
    meeting_id      UUID          NOT NULL,
    tenant_id       UUID          NOT NULL,
    org_id          UUID,
    analysis_run_id UUID          NOT NULL,
    event_type      VARCHAR(64)   NOT NULL,
    payload         TEXT          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    published_at    TIMESTAMPTZ,
    CONSTRAINT pk_meeting_analysis_outbox_events PRIMARY KEY (id),
    CONSTRAINT meeting_analysis_outbox_events_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT fk_meeting_analysis_outbox_events_analysis_run
        FOREIGN KEY (analysis_run_id) REFERENCES meeting_analysis_runs(id) ON DELETE CASCADE
);

CREATE INDEX idx_meeting_analysis_outbox_events_unpublished
    ON meeting_analysis_outbox_events(created_at)
    WHERE published_at IS NULL;
CREATE INDEX idx_meeting_analysis_outbox_events_org_id ON meeting_analysis_outbox_events(org_id);

DROP TRIGGER IF EXISTS meeting_analysis_outbox_events_org_id_compat ON meeting_analysis_outbox_events;
CREATE TRIGGER meeting_analysis_outbox_events_org_id_compat
    BEFORE INSERT OR UPDATE ON meeting_analysis_outbox_events
    FOR EACH ROW EXECUTE FUNCTION meeting_org_id_compat_fill();
