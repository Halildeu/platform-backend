-- Faz 24 #863: meeting.recording.finished uses the existing transactional outbox
-- without changing the applied V4/V5 migration bytes.

ALTER TABLE meeting_event_outbox
    ADD COLUMN aggregate_type VARCHAR(64),
    ADD COLUMN aggregate_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN payload_raw TEXT;

UPDATE meeting_event_outbox
SET aggregate_type = 'meeting.analysis.run';

ALTER TABLE meeting_event_outbox
    ALTER COLUMN aggregate_type SET DEFAULT 'meeting.analysis.run',
    ALTER COLUMN aggregate_type SET NOT NULL,
    DROP CONSTRAINT meeting_event_outbox_event_type_known,
    DROP CONSTRAINT fk_meeting_event_outbox_run;

-- Keep legacy analysis defaults during a rolling deploy. Old pods do not know
-- these additive columns; the scope CHECK still limits their rows to revision 0.

-- Generated scope columns preserve real FKs while allowing one outbox table to
-- reference distinct aggregate tables. Exactly one is populated by the scope CHECK.
ALTER TABLE meeting_event_outbox
    ADD COLUMN analysis_run_scope_id UUID GENERATED ALWAYS AS (
        CASE WHEN aggregate_type = 'meeting.analysis.run' THEN aggregate_id END
    ) STORED,
    ADD COLUMN recording_session_scope_id UUID GENERATED ALWAYS AS (
        CASE WHEN aggregate_type = 'meeting.recording' THEN aggregate_id END
    ) STORED,
    ADD CONSTRAINT meeting_event_outbox_event_type_known
        CHECK (event_type IN (
            'meeting.summary.ready',
            'meeting.action.assigned',
            'meeting.recording.finished'
        )),
    ADD CONSTRAINT meeting_event_outbox_aggregate_scope_known
        CHECK (
            (event_type IN ('meeting.summary.ready', 'meeting.action.assigned')
                AND aggregate_type = 'meeting.analysis.run'
                AND aggregate_revision = 0)
            OR
            (event_type = 'meeting.recording.finished'
                AND aggregate_type = 'meeting.recording'
                AND aggregate_revision = 1
                AND payload_raw IS NOT NULL)
        ),
    ADD CONSTRAINT meeting_event_outbox_payload_raw_matches_json
        CHECK (payload_raw IS NULL OR payload_raw::jsonb = payload);

CREATE UNIQUE INDEX uq_meeting_sessions_id_tenant_meeting
    ON meeting_sessions(id, tenant_id, meeting_id);

ALTER TABLE meeting_event_outbox
    ADD CONSTRAINT fk_meeting_event_outbox_run_scope
        FOREIGN KEY (analysis_run_scope_id, tenant_id, meeting_id)
        REFERENCES meeting_analysis_runs(analysis_run_id, tenant_id, meeting_id)
        ON DELETE CASCADE,
    ADD CONSTRAINT fk_meeting_event_outbox_recording_scope
        FOREIGN KEY (recording_session_scope_id, tenant_id, meeting_id)
        REFERENCES meeting_sessions(id, tenant_id, meeting_id)
        ON DELETE CASCADE;

CREATE INDEX idx_meeting_event_outbox_aggregate_scope
    ON meeting_event_outbox(aggregate_type, aggregate_id, aggregate_revision);

COMMENT ON COLUMN meeting_event_outbox.aggregate_type IS
    'Producer-owned aggregate scope. DB CHECK binds each event type to its allowed scope.';
COMMENT ON COLUMN meeting_event_outbox.aggregate_revision IS
    'Occurrence revision rendered in the deterministic event key; recording.finished is exactly revision 1.';
COMMENT ON COLUMN meeting_event_outbox.payload_raw IS
    'Canonical serializer bytes before JSONB normalization; nullable only for legacy rolling-deploy writers.';
