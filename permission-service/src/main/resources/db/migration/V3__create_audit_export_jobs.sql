CREATE TABLE IF NOT EXISTS audit_export_jobs (
    id VARCHAR(64) PRIMARY KEY,
    requested_by VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    format VARCHAR(16) NOT NULL,
    content_type VARCHAR(128),
    filename VARCHAR(255),
    event_count INTEGER,
    sort_value VARCHAR(128),
    filter_snapshot TEXT,
    error_message VARCHAR(2000),
    payload BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_audit_export_jobs_requested_by
    ON audit_export_jobs (requested_by);

CREATE INDEX IF NOT EXISTS idx_audit_export_jobs_status
    ON audit_export_jobs (status);
