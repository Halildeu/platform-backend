-- Separate incomplete closure occurrence; never emit recording.finished for lost/unknown audio.
-- Existing recording_scope_id tenant/meeting FK and unique event_key remain in force.
ALTER TABLE meeting_event_outbox
    DROP CONSTRAINT meeting_event_outbox_event_type_known,
    DROP CONSTRAINT meeting_event_outbox_aggregate_scope_known,
    ADD CONSTRAINT meeting_event_outbox_event_type_known CHECK (event_type IN (
        'meeting.summary.ready', 'meeting.action.assigned', 'meeting.recording.finished',
        'meeting.recording.incomplete', 'meeting.action.reassigned'
    )),
    ADD CONSTRAINT meeting_event_outbox_aggregate_scope_known CHECK (
        (event_type IN ('meeting.summary.ready', 'meeting.action.assigned')
            AND aggregate_type = 'meeting.analysis.run' AND aggregate_revision = 0)
        OR (event_type IN ('meeting.recording.finished', 'meeting.recording.incomplete')
            AND aggregate_type = 'meeting.recording' AND aggregate_revision = 1
            AND payload_raw IS NOT NULL)
        OR (event_type = 'meeting.action.reassigned' AND aggregate_type = 'meeting.action'
            AND aggregate_revision >= 0 AND payload_raw IS NOT NULL)
    );
