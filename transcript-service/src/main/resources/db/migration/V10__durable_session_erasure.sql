-- Permanent transcript-side privacy fence. A row is created as soon as an
-- erasure request is observed, including while legal hold delays destruction.
CREATE TABLE transcript_session_erasure_tombstones (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID         NOT NULL,
    org_id                  UUID,
    meeting_id              UUID         NOT NULL,
    session_id              UUID         NOT NULL,
    source_session_hash     VARCHAR(64),
    status                  VARCHAR(16)  NOT NULL,
    segment_deleted_count   INTEGER      NOT NULL DEFAULT 0,
    finalization_deleted_count INTEGER   NOT NULL DEFAULT 0,
    association_deleted_count INTEGER    NOT NULL DEFAULT 0,
    requested_at            TIMESTAMPTZ  NOT NULL,
    completed_at            TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ  NOT NULL,
    version                 BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT transcript_erasure_tombstone_org_match
        CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT transcript_erasure_tombstone_hash
        CHECK (source_session_hash IS NULL OR source_session_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT transcript_erasure_tombstone_status
        CHECK (status IN ('HELD', 'COMPLETE')),
    CONSTRAINT transcript_erasure_tombstone_counts
        CHECK (segment_deleted_count >= 0 AND finalization_deleted_count >= 0
            AND association_deleted_count >= 0),
    CONSTRAINT transcript_erasure_tombstone_completion
        CHECK ((status = 'COMPLETE' AND completed_at IS NOT NULL)
            OR (status = 'HELD' AND completed_at IS NULL)),
    CONSTRAINT ux_transcript_erasure_tombstone_session
        UNIQUE (tenant_id, meeting_id, session_id)
);

CREATE UNIQUE INDEX ux_transcript_erasure_tombstone_source_hash
    ON transcript_session_erasure_tombstones(tenant_id, meeting_id, source_session_hash)
    WHERE source_session_hash IS NOT NULL;

CREATE TABLE transcript_session_erasure_audit (
    id                          UUID PRIMARY KEY,
    tombstone_id                UUID         NOT NULL,
    state                       VARCHAR(16)  NOT NULL,
    segment_deleted_count       INTEGER      NOT NULL DEFAULT 0,
    finalization_deleted_count  INTEGER      NOT NULL DEFAULT 0,
    association_deleted_count   INTEGER      NOT NULL DEFAULT 0,
    audit_payload               VARCHAR(32)  NOT NULL DEFAULT 'metadata-only',
    executed_at                 TIMESTAMPTZ  NOT NULL,
    CONSTRAINT transcript_session_erasure_audit_state
        CHECK (state IN ('HELD', 'COMPLETE')),
    CONSTRAINT transcript_session_erasure_audit_counts
        CHECK (segment_deleted_count >= 0 AND finalization_deleted_count >= 0
            AND association_deleted_count >= 0),
    CONSTRAINT transcript_session_erasure_audit_payload
        CHECK (audit_payload = 'metadata-only')
);

CREATE INDEX idx_transcript_session_erasure_audit_tombstone
    ON transcript_session_erasure_audit(tombstone_id, executed_at DESC);

COMMENT ON TABLE transcript_session_erasure_tombstones IS
    'Permanent canonical/source-session replay fence. Contains no transcript, raw source alias, token or free-form error.';
COMMENT ON TABLE transcript_session_erasure_audit IS
    'Append-only metadata-only destruction evidence.';
