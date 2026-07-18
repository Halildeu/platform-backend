-- Producer-owned analysis authorization ledger for canonical transcript reads.
-- Legacy occurrences remain NULL and fail closed because their already-published
-- v1 events did not carry an analysisRunId. New application writes always set it.
ALTER TABLE transcript_finalizations
    ADD COLUMN analysis_run_id UUID;

CREATE UNIQUE INDEX ux_transcript_finalizations_analysis_run
    ON transcript_finalizations (analysis_run_id)
    WHERE analysis_run_id IS NOT NULL;

COMMENT ON COLUMN transcript_finalizations.analysis_run_id IS
    'Unguessable producer-minted identity emitted with this exact ready occurrence; NULL only for legacy pre-V9 rows.';
