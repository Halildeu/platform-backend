-- ============================================================================
-- V12 - Direct-STT window identity moves from producer counter to chunk range
--
-- V6 keyed replay identity on (session, source_window_seq). Live evidence
-- (2026-07-26, k3d-test session SES-1cb17fb3): audio-gateway restarts the
-- window counter inside a single source session, so the same window_seq
-- arrives twice carrying different audio — window 66 was stored from chunks
-- 133-135 and later re-sent for chunks 165-167. The unique key protected the
-- counter instead of the audio, so ingestion classified the second window as a
-- replay conflict and dropped it to the DLQ. Roughly one in ten windows was
-- lost, silently.
--
-- The chunk range is the real coordinate: it is assigned by the audio pipeline,
-- is monotonic inside a session, and identifies exactly which audio a window
-- represents. Keying on it keeps genuine replay detection (same chunks +
-- different content still conflicts) while a restarted counter no longer
-- collides.
--
-- Verified before applying: 0 duplicate (tenant, meeting, session, first, last)
-- groups in the live test dataset.
-- ============================================================================

DROP INDEX IF EXISTS ux_transcript_segments_direct_stt_window;

CREATE UNIQUE INDEX ux_transcript_segments_direct_stt_chunk_window
    ON transcript_segments
       (tenant_id, meeting_id, source_session_id,
        source_first_chunk_seq, source_last_chunk_seq)
    WHERE source_system = 'DIRECT_STT'
      AND source_session_id IS NOT NULL
      AND source_first_chunk_seq IS NOT NULL
      AND source_last_chunk_seq IS NOT NULL;

-- source_window_seq stays as producer-reported ordering metadata; it is no
-- longer an identity. The ordering index keeps serving transcript reads.
COMMENT ON COLUMN transcript_segments.source_window_seq IS
    'Producer aggregation-window sequence (ordering metadata only; the producer '
    'may restart it inside a session, so it is not a replay identity).';
COMMENT ON INDEX ux_transcript_segments_direct_stt_chunk_window IS
    'Direct-STT replay identity: the audio chunk range a window represents.';
