-- ============================================================================
-- V4 - canonical meeting-session association for Direct-STT (#802 transcript slice)
--
-- meeting_sessions belongs to meeting-service. This table is a transcript-local,
-- content-free projection resolved through the canonical service API; there is no
-- cross-service FK or shared database access.
-- ============================================================================

CREATE TABLE transcript_session_associations (
    id                      UUID         NOT NULL PRIMARY KEY,
    tenant_id               UUID         NOT NULL,
    org_id                  UUID,
    meeting_id              UUID         NOT NULL,
    source_system           VARCHAR(64)  NOT NULL,
    source_session_id       VARCHAR(128) NOT NULL,
    session_id              UUID,
    status                  VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    resolution_attempts     INTEGER      NOT NULL DEFAULT 0,
    next_retry_at           TIMESTAMPTZ,
    claim_token             UUID,
    lease_expires_at        TIMESTAMPTZ,
    last_error_code         VARCHAR(64),
    finalization_version    BIGINT       NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ  NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL,
    version                 BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT transcript_session_association_org_match
        CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT transcript_session_association_source_format
        CHECK (source_system <> 'DIRECT_STT'
            OR status = 'DEAD'
            OR source_session_id ~ '^SES-[A-Za-z0-9._:-]{1,124}$'),
    CONSTRAINT transcript_session_association_status
        CHECK (status IN ('PENDING', 'RESOLVING', 'RESOLVED', 'DEAD')),
    CONSTRAINT transcript_session_association_attempts
        CHECK (resolution_attempts >= 0),
    CONSTRAINT transcript_session_association_finalization
        CHECK (finalization_version >= 0),
    CONSTRAINT transcript_session_association_resolution_shape
        CHECK ((status = 'RESOLVED' AND session_id IS NOT NULL)
            OR (status <> 'RESOLVED' AND session_id IS NULL)),
    CONSTRAINT uq_transcript_session_association_source
        UNIQUE (tenant_id, meeting_id, source_system, source_session_id),
    CONSTRAINT uq_transcript_session_association_canonical
        UNIQUE (tenant_id, meeting_id, session_id)
);

CREATE INDEX idx_transcript_session_association_due
    ON transcript_session_associations (status, next_retry_at, created_at)
    WHERE status IN ('PENDING', 'RESOLVING');

CREATE INDEX idx_transcript_session_association_org
    ON transcript_session_associations (org_id, meeting_id, session_id);

DROP TRIGGER IF EXISTS transcript_session_association_org_id_compat
    ON transcript_session_associations;
CREATE TRIGGER transcript_session_association_org_id_compat
    BEFORE INSERT OR UPDATE ON transcript_session_associations
    FOR EACH ROW EXECUTE FUNCTION transcript_org_id_compat_fill();

-- V2 keyed replay only by tenant/source/chunk. The canonical boundary is
-- tenant + meeting + source session, so two meetings may not collide merely
-- because an external recorder id was reused.
DROP INDEX IF EXISTS ux_transcript_segments_direct_stt_chunk;
CREATE UNIQUE INDEX ux_transcript_segments_direct_stt_chunk
    ON transcript_segments
       (tenant_id, meeting_id, source_session_id, source_chunk_seq)
    WHERE source_system = 'DIRECT_STT'
      AND source_session_id IS NOT NULL
      AND source_chunk_seq IS NOT NULL;

-- Seed metadata-only pending reconciliation rows for segments written before
-- canonical association existed. md5(...)::uuid is deterministic, so rerunning
-- the migration logic during recovery cannot create a second projection row.
INSERT INTO transcript_session_associations
    (id, tenant_id, org_id, meeting_id, source_system, source_session_id,
     status, resolution_attempts, last_error_code, finalization_version,
     created_at, updated_at, version)
SELECT
    md5(s.tenant_id::text || '|' || s.meeting_id::text || '|DIRECT_STT|'
        || s.source_session_id)::uuid,
    s.tenant_id,
    COALESCE(s.org_id, s.tenant_id),
    s.meeting_id,
    'DIRECT_STT',
    s.source_session_id,
    CASE WHEN s.source_session_id ~ '^SES-[A-Za-z0-9._:-]{1,124}$'
        THEN 'PENDING' ELSE 'DEAD' END,
    0,
    CASE WHEN s.source_session_id ~ '^SES-[A-Za-z0-9._:-]{1,124}$'
        THEN NULL ELSE 'INVALID_SOURCE_SESSION_ID' END,
    0,
    NOW(),
    NOW(),
    0
FROM transcript_segments s
WHERE s.source_system = 'DIRECT_STT'
  AND s.source_session_id IS NOT NULL
  AND s.session_id IS NULL
GROUP BY s.tenant_id, COALESCE(s.org_id, s.tenant_id), s.meeting_id, s.source_session_id
ON CONFLICT (tenant_id, meeting_id, source_system, source_session_id) DO NOTHING;

COMMENT ON TABLE transcript_session_associations IS
    'Content-free transcript-local projection of meeting-service session identity; tenant+meeting scoped, no cross-service FK.';
COMMENT ON COLUMN transcript_session_associations.last_error_code IS
    'Bounded metadata-only resolver outcome code; never exception text, transcript, audio, token or PII.';
