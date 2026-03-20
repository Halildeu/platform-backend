CREATE TABLE IF NOT EXISTS auth_audit_events (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    performed_by BIGINT NOT NULL,
    details VARCHAR(2000),
    user_email VARCHAR(255) NOT NULL,
    service VARCHAR(255) NOT NULL,
    level VARCHAR(50) NOT NULL,
    action VARCHAR(100) NOT NULL,
    correlation_id VARCHAR(255) NOT NULL,
    metadata TEXT,
    occurred_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_auth_audit_events_type ON auth_audit_events(event_type);
CREATE INDEX IF NOT EXISTS idx_auth_audit_events_user ON auth_audit_events(user_email);
CREATE INDEX IF NOT EXISTS idx_auth_audit_events_service ON auth_audit_events(service);
CREATE INDEX IF NOT EXISTS idx_auth_audit_events_correlation ON auth_audit_events(correlation_id);
