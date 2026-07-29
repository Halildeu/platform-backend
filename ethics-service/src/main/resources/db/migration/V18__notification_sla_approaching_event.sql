-- ES-301 (#882). The vocabulary moves in both places at once — the lesson V12 exists to
-- record: #1011 added SLA_BREACH to the Java allowlist only, and the sweeper logged success
-- while the database dropped the row at commit. The parity test in SlaBreachSweeperTest now
-- compares the two lists, and this constraint stays the fail-closed half for any writer that
-- forgets one side.
ALTER TABLE ethics_notification_outbox DROP CONSTRAINT ck_ethics_notification_event;

ALTER TABLE ethics_notification_outbox
    ADD CONSTRAINT ck_ethics_notification_event
    CHECK (event_type IN ('NEW_REPORT', 'REPORTER_MESSAGE', 'SLA_BREACH', 'SLA_APPROACHING'));
