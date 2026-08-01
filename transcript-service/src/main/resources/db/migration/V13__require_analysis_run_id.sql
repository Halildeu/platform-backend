-- Transcript-ready consumers may replay the retained stream from 0-0. Every
-- finalization occurrence must therefore be bound to a producer-owned analysis
-- identity before the consumer can be enabled. Legacy rows are not guessed or
-- silently rewritten: operators must remediate them with an audited mapping.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM transcript_finalizations
        WHERE analysis_run_id IS NULL
    ) THEN
        RAISE EXCEPTION
            'transcript_finalizations.analysis_run_id contains legacy NULL rows; remediate before V13'
            USING ERRCODE = '23502';
    END IF;
END
$$;

ALTER TABLE transcript_finalizations
    ALTER COLUMN analysis_run_id SET NOT NULL;

COMMENT ON COLUMN transcript_finalizations.analysis_run_id IS
    'Producer-minted identity for the exact finalization occurrence; mandatory for every row.';
