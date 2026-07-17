-- Faz 24 #854: stable audio-gateway identity for idempotent desktop lifecycle sync.
ALTER TABLE meeting_sessions
    ADD COLUMN external_session_id VARCHAR(128);

ALTER TABLE meeting_sessions
    ADD CONSTRAINT meeting_sessions_external_session_id_format
        CHECK (external_session_id IS NULL OR external_session_id ~ '^[A-Za-z0-9._:-]{1,128}$');

ALTER TABLE meeting_sessions
    ADD CONSTRAINT meeting_sessions_recording_time_order
        CHECK (ended_at IS NULL OR started_at IS NULL OR ended_at >= started_at);

CREATE UNIQUE INDEX uq_meeting_sessions_external_session
    ON meeting_sessions(meeting_id, tenant_id, external_session_id)
    WHERE external_session_id IS NOT NULL;
