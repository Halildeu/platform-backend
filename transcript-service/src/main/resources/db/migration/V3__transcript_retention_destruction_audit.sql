-- V3 — Faz 24 #156 transcript retention cleanup audit.
--
-- Destruction audit is metadata-only: no transcript text, search term,
-- accessor subject, audio path, or participant identifier is persisted here.
-- The scheduled cleanup job writes this row in the same transaction as bounded
-- id-only deletes of expired rows.

CREATE TABLE transcript_retention_destruction_audit (
    id              UUID         NOT NULL PRIMARY KEY,
    layer_id        VARCHAR(96)  NOT NULL,
    cutoff_at       TIMESTAMPTZ  NOT NULL,
    deleted_count   BIGINT       NOT NULL,
    job_id          VARCHAR(96)  NOT NULL,
    audit_payload   VARCHAR(32)  NOT NULL DEFAULT 'metadata-only',
    executed_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT transcript_retention_audit_payload_ck
        CHECK (audit_payload IN ('metadata-only', 'id-only', 'transcript-free')),
    CONSTRAINT transcript_retention_audit_count_ck
        CHECK (deleted_count >= 0)
);

CREATE INDEX idx_transcript_retention_audit_layer_executed
    ON transcript_retention_destruction_audit(layer_id, executed_at);
CREATE INDEX idx_transcript_retention_audit_job_id
    ON transcript_retention_destruction_audit(job_id);
CREATE INDEX idx_transcript_segments_created_at
    ON transcript_segments(created_at);
CREATE INDEX idx_transcript_access_audit_accessed_at
    ON transcript_access_audit(accessed_at);

COMMENT ON TABLE transcript_retention_destruction_audit IS
    'Faz 24 #156 KVKK m.7 metadata-only destruction audit for transcript retention cleanup. No transcript/search/accessor content.';
COMMENT ON COLUMN transcript_retention_destruction_audit.audit_payload IS
    'Must remain metadata-only/id-only/transcript-free; never stores transcript, search term, audio path, participant, prompt, or response text.';
