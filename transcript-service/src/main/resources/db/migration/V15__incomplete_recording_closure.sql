-- Closure kind is authoritative metadata, independent of analysis success.
ALTER TABLE transcript_meeting_event_inbox DROP CONSTRAINT transcript_meeting_event_inbox_type,
    ADD CONSTRAINT transcript_meeting_event_inbox_type CHECK (
        event_type IN ('meeting.recording.finished', 'meeting.recording.incomplete'));
ALTER TABLE transcript_session_associations
    ADD COLUMN recording_outcome VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN recording_incomplete_reason VARCHAR(32);
-- Before this migration only recording.finished could set the closure timestamp.
UPDATE transcript_session_associations SET recording_outcome = 'FINISHED'
    WHERE recording_finished_at IS NOT NULL;
ALTER TABLE transcript_session_associations ADD CONSTRAINT ck_recording_closure_outcome CHECK (
    (recording_outcome = 'UNKNOWN' AND recording_finished_at IS NULL AND recording_incomplete_reason IS NULL)
    OR (recording_outcome = 'FINISHED' AND recording_finished_at IS NOT NULL AND recording_incomplete_reason IS NULL)
    OR (recording_outcome = 'INCOMPLETE' AND recording_finished_at IS NOT NULL AND recording_incomplete_reason IS NOT NULL
        AND recording_incomplete_reason = 'CLOSURE_UNCONFIRMED'));
ALTER TABLE transcript_finalizations
    ADD COLUMN recording_outcome VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN recording_incomplete_reason VARCHAR(32),
    ADD CONSTRAINT ck_finalization_recording_outcome CHECK (
        (recording_outcome IN ('UNKNOWN', 'FINISHED') AND recording_incomplete_reason IS NULL)
        OR (recording_outcome = 'INCOMPLETE' AND recording_incomplete_reason IS NOT NULL
            AND recording_incomplete_reason = 'CLOSURE_UNCONFIRMED'));
-- Historical snapshots remain UNKNOWN: a later association state is not snapshot evidence.
CREATE FUNCTION preserve_recording_closure_outcome() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.recording_outcome <> 'UNKNOWN' AND
        (NEW.recording_outcome IS DISTINCT FROM OLD.recording_outcome
        OR NEW.recording_incomplete_reason IS DISTINCT FROM OLD.recording_incomplete_reason
        OR NEW.recording_finished_at IS DISTINCT FROM OLD.recording_finished_at) THEN
        RAISE EXCEPTION 'recording closure is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER preserve_recording_closure_outcome BEFORE UPDATE ON transcript_session_associations
    FOR EACH ROW EXECUTE FUNCTION preserve_recording_closure_outcome();
CREATE FUNCTION preserve_finalization_recording_outcome() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.recording_outcome IS DISTINCT FROM OLD.recording_outcome
        OR NEW.recording_incomplete_reason IS DISTINCT FROM OLD.recording_incomplete_reason THEN
        RAISE EXCEPTION 'snapshot recording outcome is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER preserve_finalization_recording_outcome BEFORE UPDATE ON transcript_finalizations
    FOR EACH ROW EXECUTE FUNCTION preserve_finalization_recording_outcome();
