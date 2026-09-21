-- Separate HTTP delivery retries from successful Redis domain-event publication.
-- Only an occurrence reference is stored; existing source erasure cascades here.
CREATE TABLE notification_delivery_outbox (
    source_id UUID PRIMARY KEY REFERENCES meeting_event_outbox(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','DELIVERED','DEAD')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(128)
);
CREATE INDEX idx_notification_delivery_pending ON notification_delivery_outbox(next_attempt_at, source_id)
    WHERE status = 'PENDING';