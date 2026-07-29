-- ES-301. The notification event vocabulary is enumerated twice: once in
-- NotificationOutboxPublisher.ALLOWED_EVENTS and once in this CHECK constraint. #1011 added
-- SLA_BREACH to the Java allowlist only, so the sweeper ran, logged that it had enqueued a
-- signal, and then lost it when the transaction hit the database:
--
--   ERROR: new row for relation "ethics_notification_outbox" violates check constraint
--          "ck_ethics_notification_event"
--
-- The unit tests could not see it — they mock the repository, so nothing reaches Postgres.
-- The two lists have to move together, and the guard for that is the constraint itself:
-- a third writer that forgets this file fails closed rather than silently dropping signals.
ALTER TABLE ethics_notification_outbox DROP CONSTRAINT ck_ethics_notification_event;

ALTER TABLE ethics_notification_outbox
    ADD CONSTRAINT ck_ethics_notification_event
    CHECK (event_type IN ('NEW_REPORT', 'REPORTER_MESSAGE', 'SLA_BREACH'));
