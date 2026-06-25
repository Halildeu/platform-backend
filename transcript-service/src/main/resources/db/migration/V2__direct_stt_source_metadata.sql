-- ============================================================================
-- V2 — direct-STT transcript source metadata (Faz 24 #187)
--
-- audio-gateway direct-STT sessions are gateway IDs such as `SES-<uuid>`, while
-- transcript_segments.session_id is a nullable UUID cross-service reference.
-- Store direct-STT source identity separately so Redis replay / redelivery can
-- be reconciled by (tenant, source_session_id, source_chunk_seq) without
-- forcing non-UUID data into the meeting-service UUID column.
-- ============================================================================

ALTER TABLE transcript_segments
    ADD COLUMN source_system VARCHAR(64),
    ADD COLUMN source_event_id VARCHAR(128),
    ADD COLUMN source_session_id VARCHAR(128),
    ADD COLUMN source_chunk_seq BIGINT,
    ADD COLUMN source_sha256 VARCHAR(128),
    ADD COLUMN source_correlation_id VARCHAR(128);

CREATE UNIQUE INDEX ux_transcript_segments_direct_stt_chunk
    ON transcript_segments (tenant_id, source_session_id, source_chunk_seq)
    WHERE source_system = 'DIRECT_STT'
      AND source_session_id IS NOT NULL
      AND source_chunk_seq IS NOT NULL;

CREATE INDEX idx_transcript_segments_tenant_source_order
    ON transcript_segments (tenant_id, source_system, source_session_id, source_chunk_seq);

COMMENT ON COLUMN transcript_segments.source_system IS
    'Producer/source system for machine-generated transcript segments, e.g. DIRECT_STT.';
COMMENT ON COLUMN transcript_segments.source_event_id IS
    'Redis stream entry id or equivalent source event id. Metadata only; no transcript text.';
COMMENT ON COLUMN transcript_segments.source_session_id IS
    'Producer session id. direct-STT audio-gateway sessions are SES-* strings, not meeting-service UUIDs.';
COMMENT ON COLUMN transcript_segments.source_chunk_seq IS
    'Producer chunk sequence used for idempotent direct-STT replay reconciliation and ordered assembly.';
COMMENT ON COLUMN transcript_segments.source_sha256 IS
    'SHA-256 metadata for the accepted source audio chunk. No raw audio is stored here.';
COMMENT ON COLUMN transcript_segments.source_correlation_id IS
    'PII-safe correlation id from the audio-gateway/direct-STT path.';
