-- ES-301b (#1153). The escalation record (V25) told nobody: EscalationSweeper wrote the level
-- and the audit event, and the notification outbox had no vocabulary for it. Five events, one
-- per level, because the outbox row deliberately carries no payload (V5) — the level has to
-- live in the event type — and because the daily budget is keyed on (org_id, event_type), so
-- each level gets its own "once a day" for free. MAX_LEVELS in EthicsSlaEscalationProperties
-- is 5; the CHECK is the fail-closed half for any writer that forgets the Java side (V12).
ALTER TABLE ethics_notification_outbox DROP CONSTRAINT ck_ethics_notification_event;

ALTER TABLE ethics_notification_outbox
    ADD CONSTRAINT ck_ethics_notification_event
    CHECK (event_type IN ('NEW_REPORT', 'REPORTER_MESSAGE', 'SLA_BREACH', 'SLA_APPROACHING',
                          'CASE_ESCALATED_L1', 'CASE_ESCALATED_L2', 'CASE_ESCALATED_L3',
                          'CASE_ESCALATED_L4', 'CASE_ESCALATED_L5'));

-- "One signal per organisation, per level, per rolling day" has to hold across replicas. The
-- SLA sweeper's read-then-insert (exists → enqueue) lets two transactions on two cases of the
-- same organisation both see an empty window and both enqueue. This row is the budget: a
-- single UPDATE ... WHERE last_signal_at <= now - window claims it under the row lock, and a
-- rolled-back case transaction returns it. One row per (org, event); opened lazily.
CREATE TABLE ethics_notification_signal_window (
    org_id UUID NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    last_signal_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (org_id, event_type)
);
