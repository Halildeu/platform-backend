-- ============================================================================
-- V6 - Direct-STT source window identity
--
-- audio-gateway emits one transcript result per aggregation window. chunkSeq
-- remains a legacy alias for lastChunkSeq and is not a stable window identity.
-- ============================================================================

ALTER TABLE transcript_segments
    ADD COLUMN source_window_seq BIGINT,
    ADD COLUMN source_first_chunk_seq BIGINT,
    ADD COLUMN source_last_chunk_seq BIGINT;

-- Rows accepted before the window contract represented one chunk per result.
UPDATE transcript_segments
SET source_window_seq = source_chunk_seq,
    source_first_chunk_seq = source_chunk_seq,
    source_last_chunk_seq = source_chunk_seq
WHERE source_system = 'DIRECT_STT'
  AND source_chunk_seq IS NOT NULL;

ALTER TABLE transcript_segments
    ADD CONSTRAINT transcript_segments_source_window_range
        CHECK (
            (source_window_seq IS NULL
                AND source_first_chunk_seq IS NULL
                AND source_last_chunk_seq IS NULL)
            OR
            (source_window_seq IS NOT NULL
                AND source_first_chunk_seq IS NOT NULL
                AND source_last_chunk_seq IS NOT NULL
                AND source_window_seq >= 0
                AND source_first_chunk_seq >= 0
                AND source_last_chunk_seq >= source_first_chunk_seq)
        );

DROP INDEX IF EXISTS ux_transcript_segments_direct_stt_chunk;
CREATE UNIQUE INDEX ux_transcript_segments_direct_stt_window
    ON transcript_segments
       (tenant_id, meeting_id, source_session_id, source_window_seq)
    WHERE source_system = 'DIRECT_STT'
      AND source_session_id IS NOT NULL
      AND source_window_seq IS NOT NULL;

DROP INDEX IF EXISTS idx_transcript_segments_tenant_source_order;
CREATE INDEX idx_transcript_segments_tenant_source_order
    ON transcript_segments
       (tenant_id, source_system, source_session_id, source_window_seq);

COMMENT ON COLUMN transcript_segments.source_chunk_seq IS
    'Legacy source chunk alias. Direct-STT stores the source window lastChunkSeq.';
COMMENT ON COLUMN transcript_segments.source_window_seq IS
    'Producer aggregation-window sequence used for Direct-STT replay identity.';
COMMENT ON COLUMN transcript_segments.source_first_chunk_seq IS
    'First admitted source audio chunk represented by the transcript window.';
COMMENT ON COLUMN transcript_segments.source_last_chunk_seq IS
    'Last admitted source audio chunk represented by the transcript window.';
