-- ============================================================================
-- V1 — transcript-service baseline (Faz 24, platform-backend#411)
--
-- Two tables, both born WITH the org_id compat layer baked in (no later V29
-- back-fill migration needed — this is a greenfield service whose first
-- migration ships the canonical tenant_id (NOT NULL) + org_id (nullable) +
-- BEFORE INSERT/UPDATE trigger + CHECK (org_id IS NULL OR org_id = tenant_id)
-- shape that the older services reached only after V29/V30).
--
--   1. transcript_segments      — the CRUD resource. Holds a meeting
--                                  transcript line (KİŞİSEL VERİ: meeting
--                                  speech). meeting_id / session_id are
--                                  CROSS-SERVICE UUID references (meeting-service
--                                  owns a SEPARATE DB schema) → plain UUID
--                                  columns + indexes, NO foreign key.
--
--   2. transcript_access_audit  — KVKK Madde 12 erişim kaydı. One row per
--                                  READ / LIST / SEARCH / EXPORT of transcript
--                                  data (kim / ne zaman / hangi segment / hangi
--                                  tip). TRANSCRIPT-FREE: this table NEVER
--                                  stores segment text or the search term —
--                                  only metadata (segment_id, meeting_id,
--                                  result_count, access_type). Retention: 2 yıl
--                                  (enforced by the separate retention worker
--                                  #1250; this service only WRITES here).
--
-- org_id compat pattern (mirrors endpoint-admin V29/V30, applied at birth):
--   - tenant_id UUID NOT NULL  (canonical tenant scope)
--   - org_id    UUID           (nullable; canonical writers set it = tenant_id)
--   - BEFORE INSERT/UPDATE trigger fills org_id from tenant_id when null
--   - CHECK (org_id IS NULL OR org_id = tenant_id)  — both-set-but-different bug
--     is rejected with SQLSTATE 23514
--   - index on org_id for the effective-org read predicate
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Trigger function: BEFORE INSERT/UPDATE — fill org_id from tenant_id when the
-- caller leaves it null. Idempotent + side-effect-free for callers that already
-- supply org_id (the canonical write path). Shared by both tables.
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION transcript_org_id_compat_fill()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.org_id IS NULL AND NEW.tenant_id IS NOT NULL THEN
        NEW.org_id := NEW.tenant_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION transcript_org_id_compat_fill() IS
    'Faz 24 transcript org_id compat layer (V1). Fills org_id from tenant_id when caller leaves it null. Safe to remove after a future cleanup PR drops tenant_id.';

-- ============================================================================
-- Table 1: transcript_segments
-- ============================================================================

CREATE TABLE transcript_segments (
    id              UUID            NOT NULL PRIMARY KEY,
    tenant_id       UUID            NOT NULL,
    -- org_id compat column (canonical writers set it = tenant_id; trigger
    -- back-fills legacy/null writers). Nullable; CHECK enforces equality.
    org_id          UUID,
    -- Cross-service references (meeting-service owns a separate DB schema):
    -- plain UUID columns + indexes, NO foreign key.
    meeting_id      UUID            NOT NULL,
    session_id      UUID,
    speaker_id      UUID,
    start_time      DOUBLE PRECISION NOT NULL,
    end_time        DOUBLE PRECISION NOT NULL,
    -- The transcript text (KİŞİSEL VERİ). draft = raw ASR output;
    -- final = human/post-processing corrected text (nullable until finalized).
    text_draft      TEXT,
    text_final      TEXT,
    confidence      DOUBLE PRECISION,
    status          VARCHAR(32)     NOT NULL DEFAULT 'DRAFT',
    created_at      TIMESTAMPTZ     NOT NULL,
    updated_at      TIMESTAMPTZ     NOT NULL,
    version         BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT transcript_segments_org_id_match
        CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT transcript_segments_time_order
        CHECK (end_time >= start_time)
);

CREATE INDEX idx_transcript_segments_org_id
    ON transcript_segments (org_id);
-- Primary read selector: all segments of a meeting within a tenant, ordered.
CREATE INDEX idx_transcript_segments_tenant_meeting
    ON transcript_segments (tenant_id, meeting_id, start_time);
CREATE INDEX idx_transcript_segments_tenant_session
    ON transcript_segments (tenant_id, session_id);
CREATE INDEX idx_transcript_segments_tenant_status
    ON transcript_segments (tenant_id, status);

DROP TRIGGER IF EXISTS transcript_segments_org_id_compat ON transcript_segments;
CREATE TRIGGER transcript_segments_org_id_compat
    BEFORE INSERT OR UPDATE ON transcript_segments
    FOR EACH ROW EXECUTE FUNCTION transcript_org_id_compat_fill();

COMMENT ON TABLE transcript_segments IS
    'Meeting transcript segment (Faz 24). KİŞİSEL VERİ: meeting speech. Every read/list/search/export of this data is recorded in transcript_access_audit per KVKK m.12. meeting_id/session_id are cross-service UUID refs (meeting-service separate schema; no FK).';

-- ============================================================================
-- Table 2: transcript_access_audit  (KVKK Madde 12 access log)
--
-- TRANSCRIPT-FREE by contract: this table stores access METADATA only —
-- never the segment text and never the search term. accessor_subject is the
-- request authz principal (context-derived, never request-body supplied).
-- segment_id is nullable (LIST / SEARCH access types are meeting/tenant-scoped
-- and may not target a single segment). result_count records how many rows a
-- LIST / SEARCH / EXPORT returned.
--
-- Retention: 2 yıl (KVKK m.12 erişim kaydı). Enforced by the separate retention
-- worker (#1250); this service is a WRITE-only producer for this table.
-- ============================================================================

CREATE TABLE transcript_access_audit (
    id                UUID          NOT NULL PRIMARY KEY,
    tenant_id         UUID          NOT NULL,
    org_id            UUID,
    -- Who accessed (request authz principal, context-derived — NOT body input).
    accessor_subject  VARCHAR(255)  NOT NULL,
    -- What was accessed. segment_id nullable for LIST/SEARCH (meeting-scoped).
    segment_id        UUID,
    meeting_id        UUID,
    session_id        UUID,
    -- READ / LIST / SEARCH / EXPORT (validated by the AccessType enum).
    access_type       VARCHAR(16)   NOT NULL,
    accessed_at       TIMESTAMPTZ   NOT NULL,
    -- How many rows a LIST/SEARCH/EXPORT returned (null for single READ).
    result_count      INTEGER,

    CONSTRAINT transcript_access_audit_org_id_match
        CHECK (org_id IS NULL OR org_id = tenant_id)
);

CREATE INDEX idx_transcript_access_audit_org_id
    ON transcript_access_audit (org_id);
-- Retention worker + per-tenant audit reporting selector.
CREATE INDEX idx_transcript_access_audit_tenant_accessed
    ON transcript_access_audit (tenant_id, accessed_at);

DROP TRIGGER IF EXISTS transcript_access_audit_org_id_compat ON transcript_access_audit;
CREATE TRIGGER transcript_access_audit_org_id_compat
    BEFORE INSERT OR UPDATE ON transcript_access_audit
    FOR EACH ROW EXECUTE FUNCTION transcript_org_id_compat_fill();

COMMENT ON TABLE transcript_access_audit IS
    'KVKK Madde 12 access log for transcript personal data. TRANSCRIPT-FREE: metadata only (segment_id, counts, access_type) — never segment text nor search term. 2yr retention enforced by retention worker #1250; this service only WRITES here.';

COMMENT ON COLUMN transcript_access_audit.accessor_subject IS
    'Request authz principal (context-derived; never request-body supplied). KVKK m.12 "kim".';
COMMENT ON COLUMN transcript_access_audit.access_type IS
    'READ | LIST | SEARCH | EXPORT. KVKK m.12 erişim tipi.';
