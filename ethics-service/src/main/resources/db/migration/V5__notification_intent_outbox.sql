-- Faz 35 ES-208 — case-independent, no-PII notification intent outbox.
--
-- The table intentionally has no case/report/message/receipt foreign key and
-- no free-form payload. Business commit + signal insert share one transaction;
-- provider delivery happens later through a bounded lease/retry/DLQ worker.

CREATE TABLE ethics_notification_outbox (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    claim_token UUID,
    locked_until TIMESTAMP WITH TIME ZONE,
    delivered_at TIMESTAMP WITH TIME ZONE,
    last_error_code VARCHAR(120),
    CONSTRAINT ck_ethics_notification_event
        CHECK (event_type IN ('NEW_REPORT','REPORTER_MESSAGE')),
    CONSTRAINT ck_ethics_notification_status
        CHECK (status IN ('PENDING','PROCESSING','DELIVERED','DEAD_LETTER'))
);

CREATE INDEX ix_ethics_notification_delivery_due
    ON ethics_notification_outbox(status, next_attempt_at, created_at);
CREATE INDEX ix_ethics_notification_claim_token
    ON ethics_notification_outbox(claim_token);
