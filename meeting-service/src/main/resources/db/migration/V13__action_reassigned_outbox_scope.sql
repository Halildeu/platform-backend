-- Faz 24 Görevler dilim-4 — manual assignment events on the ACTION aggregate.
--
-- meeting.action.reassigned is emitted by the manual task CRUD when an action
-- gains or changes its assignee. The aggregate is the action itself and the
-- occurrence counter is the action's optimistic-lock version, so the same
-- action can be re-assigned any number of times without key collisions
-- (V7 established this scope pattern (numbering: V10-V12 were taken by transcript-readback/history/agenda) for meeting.recording.finished).

-- The composite target the scope-FK needs; mirrors V7's meeting_sessions index.
CREATE UNIQUE INDEX uq_meeting_actions_id_tenant_meeting
    ON meeting_actions(id, tenant_id, meeting_id);

ALTER TABLE meeting_event_outbox
    DROP CONSTRAINT meeting_event_outbox_event_type_known,
    DROP CONSTRAINT meeting_event_outbox_aggregate_scope_known,
    ADD COLUMN action_scope_id UUID GENERATED ALWAYS AS (
        CASE WHEN aggregate_type = 'meeting.action' THEN aggregate_id END
    ) STORED,
    ADD CONSTRAINT meeting_event_outbox_event_type_known
        CHECK (event_type IN (
            'meeting.summary.ready',
            'meeting.action.assigned',
            'meeting.recording.finished',
            'meeting.action.reassigned'
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
            OR
            -- revision >= 0: a task created WITH an assignee emits at version 0;
            -- every later hand-over emits at the flushed post-update version.
            (event_type = 'meeting.action.reassigned'
                AND aggregate_type = 'meeting.action'
                AND aggregate_revision >= 0
                AND payload_raw IS NOT NULL)
        );

-- ON DELETE CASCADE mirrors V7: tearing down the aggregate takes its undelivered
-- outbox rows with it (deleteAction is a real admin surface).
ALTER TABLE meeting_event_outbox
    ADD CONSTRAINT fk_meeting_event_outbox_action_scope
        FOREIGN KEY (action_scope_id, tenant_id, meeting_id)
        REFERENCES meeting_actions(id, tenant_id, meeting_id)
        ON DELETE CASCADE;

COMMENT ON COLUMN meeting_event_outbox.action_scope_id IS
    'Populated only for meeting.action-scoped events; carries the FK to meeting_actions.';
