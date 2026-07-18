-- Content-free replay fence for Direct-STT windows destroyed by retention.
CREATE TABLE transcript_source_retention_fences (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID         NOT NULL,
    org_id              UUID,
    meeting_id          UUID         NOT NULL,
    session_id          UUID,
    source_session_hash VARCHAR(64)  NOT NULL,
    source_window_seq   BIGINT       NOT NULL,
    retained_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT transcript_source_retention_org_match
        CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT transcript_source_retention_hash
        CHECK (source_session_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT transcript_source_retention_window
        CHECK (source_window_seq >= 0),
    CONSTRAINT ux_transcript_source_retention_window
        UNIQUE (tenant_id, meeting_id, source_session_hash, source_window_seq)
);

CREATE INDEX idx_transcript_source_retention_session
    ON transcript_source_retention_fences(tenant_id, meeting_id, session_id)
    WHERE session_id IS NOT NULL;

COMMENT ON TABLE transcript_source_retention_fences IS
    'Permanent metadata-only replay fence for Direct-STT windows destroyed by retention. Never stores transcript, raw source aliases, tokens, or free-form errors.';
