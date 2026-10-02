-- Only signed snapshot provenance may qualify a new result. Historical rows stay UNKNOWN.
ALTER TABLE meeting_analysis_runs
    ADD COLUMN recording_outcome VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN recording_incomplete_reason VARCHAR(32),
    ADD CONSTRAINT ck_analysis_recording_outcome CHECK (
        (recording_outcome IN ('UNKNOWN', 'FINISHED') AND recording_incomplete_reason IS NULL)
        OR (recording_outcome = 'INCOMPLETE' AND recording_incomplete_reason IS NOT NULL
            AND recording_incomplete_reason = 'CLOSURE_UNCONFIRMED'));

CREATE FUNCTION preserve_analysis_recording_outcome() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.recording_outcome IS DISTINCT FROM OLD.recording_outcome
        OR NEW.recording_incomplete_reason IS DISTINCT FROM OLD.recording_incomplete_reason THEN
        RAISE EXCEPTION 'analysis recording outcome is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER preserve_analysis_recording_outcome BEFORE UPDATE ON meeting_analysis_runs
    FOR EACH ROW EXECUTE FUNCTION preserve_analysis_recording_outcome();
