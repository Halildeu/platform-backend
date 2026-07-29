-- Direct-STT transport sequence-space identity migration.
-- ============================================================================
-- V12 - Direct-STT replay identity includes the gateway transport epoch
--
-- V6 keyed replay identity on (session, source_window_seq). The gateway opens
-- a new sequence space for each REST buffer or WebSocket leg, so window_seq
-- restarts inside one source session.
--
-- Chunk ranges are provenance, not identity. Live TEST evidence on 2026-07-29
-- found valid rows with the same source session and chunk range but different
-- audio digests, transcript drafts, and transport epochs. A unique chunk-range
-- index therefore rejects valid overlapping transport legs.
--
-- The gateway v1 envelope already carries transportEpoch and defines ordering
-- as (transportEpoch, windowSeq). Persisting that pair gives each reconnect or
-- transport leg an independent sequence space while preserving idempotency for
-- exact re-delivery inside the same space.
-- ============================================================================

ALTER TABLE transcript_segments
    ADD COLUMN source_transport_epoch BIGINT;

-- Events accepted before transportEpoch was persisted belong to one legacy
-- sequence space. V6 already guaranteed source_window_seq uniqueness for these
-- rows, so epoch 0 is deterministic and collision-free.
UPDATE transcript_segments
SET source_transport_epoch = 0
WHERE source_window_seq IS NOT NULL;

ALTER TABLE transcript_segments
    DROP CONSTRAINT transcript_segments_source_window_range,
    ADD CONSTRAINT transcript_segments_source_window_range
        CHECK (
            (source_transport_epoch IS NULL
                AND source_window_seq IS NULL
                AND source_first_chunk_seq IS NULL
                AND source_last_chunk_seq IS NULL)
            OR
            (source_transport_epoch IS NOT NULL
                AND source_window_seq IS NOT NULL
                AND source_first_chunk_seq IS NOT NULL
                AND source_last_chunk_seq IS NOT NULL
                AND source_transport_epoch >= 0
                AND source_window_seq >= 0
                AND source_first_chunk_seq >= 0
                AND source_last_chunk_seq >= source_first_chunk_seq)
        );

DROP INDEX IF EXISTS ux_transcript_segments_direct_stt_window;
CREATE UNIQUE INDEX ux_transcript_segments_direct_stt_transport_window
    ON transcript_segments
       (tenant_id, meeting_id, source_session_id,
        source_transport_epoch, source_window_seq)
    WHERE source_system = 'DIRECT_STT'
      AND source_session_id IS NOT NULL
      AND source_transport_epoch IS NOT NULL
      AND source_window_seq IS NOT NULL;

DROP INDEX IF EXISTS idx_transcript_segments_tenant_source_order;
CREATE INDEX idx_transcript_segments_tenant_source_order
    ON transcript_segments
       (tenant_id, source_system, source_session_id,
        source_transport_epoch, source_window_seq);

-- Retention must fence the same replay identity as active canonical storage.
ALTER TABLE transcript_source_retention_fences
    ADD COLUMN source_transport_epoch BIGINT NOT NULL DEFAULT 0,
    DROP CONSTRAINT ux_transcript_source_retention_window,
    ADD CONSTRAINT transcript_source_retention_epoch
        CHECK (source_transport_epoch >= 0),
    ADD CONSTRAINT ux_transcript_source_retention_window
        UNIQUE (
            tenant_id, meeting_id, source_session_hash,
            source_transport_epoch, source_window_seq);

ALTER TABLE transcript_source_retention_fences
    ALTER COLUMN source_transport_epoch DROP DEFAULT;

COMMENT ON COLUMN transcript_segments.source_transport_epoch IS
    'Gateway-owned sequence-space epoch. Replay identity is '
    '(source_transport_epoch, source_window_seq) inside a source session.';
COMMENT ON COLUMN transcript_segments.source_window_seq IS
    'Producer aggregation-window sequence, unique only inside '
    'source_transport_epoch.';
COMMENT ON COLUMN transcript_segments.source_first_chunk_seq IS
    'First admitted source audio chunk represented by the transcript window; '
    'provenance only, because chunk counters may restart across transport legs.';
COMMENT ON COLUMN transcript_segments.source_last_chunk_seq IS
    'Last admitted source audio chunk represented by the transcript window; '
    'provenance only, because chunk counters may restart across transport legs.';
COMMENT ON INDEX ux_transcript_segments_direct_stt_transport_window IS
    'Direct-STT replay identity: gateway transport epoch plus window sequence.';
COMMENT ON COLUMN transcript_source_retention_fences.source_transport_epoch IS
    'Gateway transport epoch paired with source_window_seq for replay fencing.';
