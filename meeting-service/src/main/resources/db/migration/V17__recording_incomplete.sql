-- V16 is reserved by the independent native notification source proposal.
ALTER TABLE meeting_sessions ADD COLUMN recording_incomplete BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE meeting_sessions ADD CONSTRAINT chk_incomplete_recording_terminal
    CHECK (NOT recording_incomplete OR (ended_at IS NOT NULL AND transcript_status = 'FAILED'));
-- Once declared incomplete it must not be promoted by a late writer, even outside JPA.
CREATE FUNCTION preserve_incomplete_recording() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.recording_incomplete AND (NOT NEW.recording_incomplete
        OR NEW.started_at IS DISTINCT FROM OLD.started_at
        OR NEW.ended_at IS DISTINCT FROM OLD.ended_at) THEN
        RAISE EXCEPTION 'incomplete recording is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER preserve_incomplete_recording BEFORE UPDATE ON meeting_sessions
    FOR EACH ROW EXECUTE FUNCTION preserve_incomplete_recording();
