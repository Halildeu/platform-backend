CREATE TABLE ethics_cases (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    product_id VARCHAR(40) NOT NULL,
    status VARCHAR(40) NOT NULL,
    assigned_to VARCHAR(200),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_ethics_product CHECK (product_id = 'etik-speak')
);
CREATE INDEX ix_ethics_cases_org_updated ON ethics_cases(org_id, updated_at DESC);

CREATE TABLE ethics_reports (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL UNIQUE REFERENCES ethics_cases(id),
    mode VARCHAR(20) NOT NULL,
    category VARCHAR(80) NOT NULL,
    subject VARCHAR(240) NOT NULL,
    narrative VARCHAR(16000) NOT NULL,
    locale VARCHAR(12) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_ethics_report_mode CHECK (mode IN ('ANONYMOUS','CONFIDENTIAL','NAMED'))
);

CREATE TABLE reporter_access_grants (
    receipt_id UUID PRIMARY KEY,
    case_id UUID NOT NULL UNIQUE REFERENCES ethics_cases(id),
    secret_hash VARCHAR(512) NOT NULL,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE mailbox_sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES ethics_cases(id),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_mailbox_sessions_expiry ON mailbox_sessions(expires_at);

CREATE TABLE ethics_messages (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES ethics_cases(id),
    author_type VARCHAR(20) NOT NULL,
    visibility VARCHAR(30) NOT NULL,
    body VARCHAR(16000) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_ethics_message_idempotency UNIQUE(case_id, author_type, idempotency_key),
    CONSTRAINT ck_ethics_message_visibility CHECK (visibility IN ('REPORTER_VISIBLE','INTERNAL'))
);
CREATE INDEX ix_ethics_messages_case_time ON ethics_messages(case_id, created_at);

CREATE TABLE ethics_audit_outbox (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    payload VARCHAR(4000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_ethics_audit_pending ON ethics_audit_outbox(status, created_at);

CREATE TABLE ethics_intake_idempotency (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    channel VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    receipt_id UUID NOT NULL REFERENCES reporter_access_grants(receipt_id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_ethics_intake_idempotency UNIQUE(org_id, channel, idempotency_key)
);

-- Narrative, reporter access hashes and audit intents are deliberately separate
-- tables. Reporter identity/link and evidence compartments arrive in their own
-- backward-compatible migrations; they must never be folded into narrative.
