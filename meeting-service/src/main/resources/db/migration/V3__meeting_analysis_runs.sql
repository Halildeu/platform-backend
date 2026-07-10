-- V3 — meeting-ai analysis-result persistence (Faz 24, platform-ai#244 BE-1a)
--
-- Today `MeetingIntelligenceService.analyze` is @Transactional(readOnly = true):
-- it proxies meeting-ai and returns `persisted=false, storageMode="preview"`.
-- The LLM's summary/decisions/actions live only in the desktop renderer's state,
-- which is why the "Toplantı Çıktısı" panel never fills, why report-service's
-- weekly-meeting-summary reads empty tables (platform-backend#413), and why the
-- `summary.ready` / `action.assigned` events cannot be emitted (#412).
--
-- ADR direction (platform-ai#244, Verdict A + Codex plan review 019f4c1e):
--   meeting-ai-service is the single server-side PRODUCER of an analysis result;
--   meeting-service is the single SYSTEM OF RECORD that writes it (one atomic
--   transaction, its own tenant/KVKK/audit authority). Desktop only triggers the
--   analysis and reads the canonical result back from meeting-service.
--
-- This migration lands the storage + invariants. The ingestion endpoint, the
-- service-token security chain and the outbox come in BE-1b/1c/1d.
--
-- IDENTITY (Codex review point 3 — "make the id vs analysis_run_id split
-- unambiguous"): there is NO separate internal surrogate key. `analysis_run_id`
-- (the caller's Idempotency-Key) IS the primary key, so a child row can only
-- ever reference the same thing the caller retried on. Composite tenant safety
-- is preserved via `uq_analysis_runs_run_tenant (analysis_run_id, tenant_id)`,
-- which the child FKs target.
--
-- WHAT IS DELIBERATELY *NOT* HERE:
--   * No `(tenant_id, meeting_id, transcript_sha256, analyzer_contract_version)`
--     unique key. An earlier draft had one; it would have made re-analysis
--     impossible and silently collapsed different prompt_version/model/backend
--     runs into "the same" canonical run. Supersession is explicit instead
--     (`supersedes_analysis_run_id`).
--   * No `transcript_revision` column. Neither transcript-service nor
--     meeting-service has such a concept, and inventing one here would be a
--     cross-service scope jump. The snapshot identity is
--     (`transcript_session_id`, `transcript_sha256`). Note the honest limit:
--     a hash identifies a snapshot, it does NOT order two snapshots — so this
--     schema supports "the submitted snapshot differs from the accepted one"
--     but NOT an authoritative "a newer transcript exists" claim.

-- ============================================================
-- Table: meeting_analysis_runs (1:N -> meetings)
-- ============================================================

CREATE TABLE meeting_analysis_runs (
    -- The caller's Idempotency-Key. Primary key on purpose: identity == idempotency.
    analysis_run_id           UUID          NOT NULL,
    meeting_id                UUID          NOT NULL,
    tenant_id                 UUID          NOT NULL,
    org_id                    UUID,

    -- Transcript snapshot this analysis was produced from. session_id is the
    -- gateway's `SES-*` string (VARCHAR, not UUID — see audio-gateway contract).
    transcript_session_id     VARCHAR(64)   NOT NULL,
    -- VARCHAR, not CHAR: CHAR is blank-padded, so a comparison against a value read
    -- back from the DB can silently differ from the hash the caller sent.
    transcript_sha256         VARCHAR(64)   NOT NULL,

    -- Producer provenance: what analysed, under which contract.
    analyzer_contract_version VARCHAR(64)   NOT NULL,
    model                     VARCHAR(128),
    backend                   VARCHAR(64),
    prompt_version            VARCHAR(64),

    -- Canonical hash of the ingestion payload. Same idempotency key + different
    -- payload must be rejected (409 IDEMPOTENCY_CONFLICT) rather than silently
    -- overwriting an accepted result. Enforced in the service layer against
    -- this stored value; the DB keeps the evidence.
    payload_hash              VARCHAR(64)   NOT NULL,

    -- Analysis output (ADR-0043 grounding/citation contract).
    summary                   TEXT,
    summary_grounding_status  VARCHAR(32),
    summary_citations         JSONB         NOT NULL DEFAULT '[]'::jsonb,
    citations                 JSONB         NOT NULL DEFAULT '[]'::jsonb,
    rejected_claims           JSONB         NOT NULL DEFAULT '[]'::jsonb,
    ungrounded_count          INTEGER       NOT NULL DEFAULT 0,
    redacted                  BOOLEAN       NOT NULL DEFAULT FALSE,
    redaction_count           INTEGER       NOT NULL DEFAULT 0,

    -- Explicit supersession: a re-analysis is a NEW run that points at the one
    -- it replaces. Nullable self-reference; ON DELETE SET NULL keeps the newer
    -- run alive if an older one is purged (KVKK retention).
    supersedes_analysis_run_id UUID,

    generated_at              TIMESTAMPTZ   NOT NULL,
    created_at                TIMESTAMPTZ   NOT NULL,
    updated_at                TIMESTAMPTZ   NOT NULL,
    version                   BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pk_meeting_analysis_runs PRIMARY KEY (analysis_run_id),
    CONSTRAINT meeting_analysis_runs_org_id_match
        CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT meeting_analysis_runs_counts_non_negative
        CHECK (ungrounded_count >= 0 AND redaction_count >= 0),
    -- Both columns are lowercase SHA-256 hex. Pinning the shape here means a caller
    -- cannot smuggle a truncated hash, a base64 digest or an empty string past the
    -- idempotency comparison.
    CONSTRAINT meeting_analysis_runs_transcript_sha256_hex
        CHECK (transcript_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT meeting_analysis_runs_payload_hash_hex
        CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    -- A run cannot supersede itself.
    CONSTRAINT meeting_analysis_runs_no_self_supersede
        CHECK (supersedes_analysis_run_id IS NULL
               OR supersedes_analysis_run_id <> analysis_run_id),

    -- Composite tenant-FK to the parent meeting (V1 cross-tenant child drift guard).
    CONSTRAINT fk_meeting_analysis_runs_meeting
        FOREIGN KEY (meeting_id, tenant_id) REFERENCES meetings(id, tenant_id) ON DELETE CASCADE,

    CONSTRAINT fk_meeting_analysis_runs_supersedes
        FOREIGN KEY (supersedes_analysis_run_id) REFERENCES meeting_analysis_runs(analysis_run_id)
        ON DELETE SET NULL
);

-- Target for the child composite FKs: keeps a child row bound to a run in the
-- SAME tenant, exactly like V1's uq_meetings_id_tenant.
CREATE UNIQUE INDEX uq_analysis_runs_run_tenant
    ON meeting_analysis_runs(analysis_run_id, tenant_id);

CREATE INDEX idx_meeting_analysis_runs_meeting_id ON meeting_analysis_runs(meeting_id);
CREATE INDEX idx_meeting_analysis_runs_org_id ON meeting_analysis_runs(org_id);
-- "Latest analysis for this meeting" read path.
CREATE INDEX idx_meeting_analysis_runs_meeting_generated
    ON meeting_analysis_runs(tenant_id, meeting_id, generated_at DESC);

DROP TRIGGER IF EXISTS meeting_analysis_runs_org_id_compat ON meeting_analysis_runs;
CREATE TRIGGER meeting_analysis_runs_org_id_compat
    BEFORE INSERT OR UPDATE ON meeting_analysis_runs
    FOR EACH ROW EXECUTE FUNCTION meeting_org_id_compat_fill();

COMMENT ON TABLE meeting_analysis_runs IS
    'Faz 24 platform-ai#244 BE-1a. Canonical meeting-ai analysis result. PK is the caller Idempotency-Key (analysis_run_id) — identity equals idempotency. Re-analysis is a new run linked via supersedes_analysis_run_id, never an overwrite.';
COMMENT ON COLUMN meeting_analysis_runs.payload_hash IS
    'Canonical hash of the ingestion payload. Same analysis_run_id with a different hash => 409 IDEMPOTENCY_CONFLICT (service layer), never a silent overwrite.';
COMMENT ON COLUMN meeting_analysis_runs.transcript_sha256 IS
    'Identifies WHICH transcript snapshot was analysed. Does NOT order snapshots: this schema cannot authoritatively answer "is a newer transcript available".';

-- ============================================================
-- Provenance columns on the child tables
-- ============================================================
--
-- meeting_actions / meeting_decisions already hold human-authored rows. AI-derived
-- rows now carry their origin so a reader (and report-service) can tell an
-- LLM-extracted, human-unverified item from one a person entered.
--
-- `ordinal` is the position within its analysis run. Together with the run id it
-- is the child idempotency key: a retried ingestion re-inserts the same
-- (analysis_run_id, ordinal) and hits the unique index instead of duplicating.
-- Content hashing is deliberately NOT the key — the same sentence may legitimately
-- appear at two ordinals.

ALTER TABLE meeting_actions
    ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN analysis_run_id UUID,
    ADD COLUMN ordinal INTEGER;

ALTER TABLE meeting_decisions
    ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN analysis_run_id UUID,
    ADD COLUMN ordinal INTEGER;

-- Provenance coherence: an AI row has BOTH a run and an ordinal; a manual row has
-- NEITHER. This makes the "half-migrated" state (run set, ordinal null) impossible
-- rather than merely unlikely.
ALTER TABLE meeting_actions
    ADD CONSTRAINT meeting_actions_provenance_coherent
        CHECK (
            (source = 'MANUAL'      AND analysis_run_id IS NULL     AND ordinal IS NULL)
         OR (source = 'AI_ANALYSIS' AND analysis_run_id IS NOT NULL AND ordinal IS NOT NULL AND ordinal >= 0)
        );

ALTER TABLE meeting_decisions
    ADD CONSTRAINT meeting_decisions_provenance_coherent
        CHECK (
            (source = 'MANUAL'      AND analysis_run_id IS NULL     AND ordinal IS NULL)
         OR (source = 'AI_ANALYSIS' AND analysis_run_id IS NOT NULL AND ordinal IS NOT NULL AND ordinal >= 0)
        );

-- Composite tenant-FK: an AI row can only bind to a run in its own tenant.
ALTER TABLE meeting_actions
    ADD CONSTRAINT fk_meeting_actions_analysis_run
        FOREIGN KEY (analysis_run_id, tenant_id)
        REFERENCES meeting_analysis_runs(analysis_run_id, tenant_id) ON DELETE CASCADE;

ALTER TABLE meeting_decisions
    ADD CONSTRAINT fk_meeting_decisions_analysis_run
        FOREIGN KEY (analysis_run_id, tenant_id)
        REFERENCES meeting_analysis_runs(analysis_run_id, tenant_id) ON DELETE CASCADE;

-- Child idempotency. Partial: manual rows (analysis_run_id IS NULL) are untouched.
CREATE UNIQUE INDEX uq_meeting_actions_run_ordinal
    ON meeting_actions(tenant_id, analysis_run_id, ordinal)
    WHERE analysis_run_id IS NOT NULL;

CREATE UNIQUE INDEX uq_meeting_decisions_run_ordinal
    ON meeting_decisions(tenant_id, analysis_run_id, ordinal)
    WHERE analysis_run_id IS NOT NULL;

COMMENT ON COLUMN meeting_actions.source IS
    'MANUAL (human-authored) or AI_ANALYSIS (extracted by meeting-ai, human-unverified). Faz 24 #244.';
COMMENT ON COLUMN meeting_actions.ordinal IS
    'Position within analysis_run_id. (tenant_id, analysis_run_id, ordinal) is the child idempotency key — content hash is NOT, since the same text may legitimately repeat.';
COMMENT ON COLUMN meeting_decisions.source IS
    'MANUAL (human-authored) or AI_ANALYSIS (extracted by meeting-ai, human-unverified). meeting-ai returns decisions[] as plain strings, so an AI decision maps to title=first 512 chars, detail=full text, decided_by_subject=NULL, decided_at=generated_at.';
