-- #867 - canonical meeting-history ordering key and tenant-scoped search indexes.
--
-- Existing rows prefer the earliest persisted recording-session start. Meetings
-- without a session fall back to their scheduled start, then creation time. The
-- insert trigger keeps legacy/direct writers compatible; the Java writer always
-- supplies started_at explicitly.

ALTER TABLE meetings
    ADD COLUMN started_at TIMESTAMPTZ;

UPDATE meetings AS meeting
SET started_at = COALESCE(
        (
            SELECT MIN(session.started_at)
            FROM meeting_sessions AS session
            WHERE session.meeting_id = meeting.id
              AND session.tenant_id = meeting.tenant_id
        ),
        meeting.scheduled_start,
        meeting.created_at
    );

ALTER TABLE meetings
    ALTER COLUMN started_at SET NOT NULL;

CREATE OR REPLACE FUNCTION meeting_started_at_fill()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.started_at IS NULL THEN
        NEW.started_at := COALESCE(NEW.scheduled_start, NEW.created_at, CURRENT_TIMESTAMP);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION meeting_started_at_fill() IS
    'Legacy-writer backstop for meetings.started_at; canonical service writers set it explicitly.';

DROP TRIGGER IF EXISTS meetings_started_at_fill ON meetings;
CREATE TRIGGER meetings_started_at_fill
    BEFORE INSERT ON meetings
    FOR EACH ROW EXECUTE FUNCTION meeting_started_at_fill();

COMMENT ON COLUMN meetings.started_at IS
    'Canonical history ordering/filter key: actual first recording start, else scheduled/created fallback.';

CREATE INDEX idx_meetings_org_started_id
    ON meetings(org_id, started_at DESC, id DESC);

CREATE INDEX idx_meetings_legacy_tenant_started_id
    ON meetings(tenant_id, started_at DESC, id DESC)
    WHERE org_id IS NULL;
