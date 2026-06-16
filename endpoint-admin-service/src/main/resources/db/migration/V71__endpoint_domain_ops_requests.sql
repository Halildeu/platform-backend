CREATE TABLE IF NOT EXISTS endpoint_domain_ops_requests (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    org_id UUID,
    device_id UUID NOT NULL,
    operation VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    reason TEXT NOT NULL,
    reason_code VARCHAR(128),
    idempotency_key_hash VARCHAR(64),
    credential_ref VARCHAR(256),
    credential_ref_hash VARCHAR(64),
    requested_by VARCHAR(255) NOT NULL,
    ttl_seconds BIGINT NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    dispatched_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    state_updated_at TIMESTAMPTZ NOT NULL,
    connector_name VARCHAR(128),
    connector_attempt_id VARCHAR(128),
    redacted_result JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_endpoint_domain_ops_state CHECK (
        state IN ('ACCEPTED','DENIED','PENDING_DISPATCH','DISPATCHED','SUCCEEDED','FAILED','EXPIRED')
    ),
    CONSTRAINT ck_endpoint_domain_ops_operation CHECK (
        operation IN ('DOMAIN_SECURE_CHANNEL_VERIFY','GPO_FORCE_REFRESH','CERT_AUTOENROLL_PULSE')
    ),
    CONSTRAINT ck_endpoint_domain_ops_org_match CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT ck_endpoint_domain_ops_ttl_positive CHECK (ttl_seconds > 0 AND ttl_seconds <= 900),
    CONSTRAINT ck_endpoint_domain_ops_credential_ref_shape CHECK (
        credential_ref IS NULL
        OR credential_ref ~ '^(vault:|os-credential:|delegated-worker:|secret-ref:)[^[:space:];|&<>]+$'
    ),
    CONSTRAINT ck_endpoint_domain_ops_redacted_result_shape CHECK (jsonb_typeof(redacted_result) = 'object'),
    CONSTRAINT fk_endpoint_domain_ops_device FOREIGN KEY (device_id) REFERENCES endpoint_devices(id)
);

CREATE INDEX IF NOT EXISTS idx_endpoint_domain_ops_tenant_state
    ON endpoint_domain_ops_requests (tenant_id, state);

CREATE INDEX IF NOT EXISTS idx_endpoint_domain_ops_device_requested
    ON endpoint_domain_ops_requests (tenant_id, device_id, requested_at DESC);

CREATE INDEX IF NOT EXISTS idx_endpoint_domain_ops_expires
    ON endpoint_domain_ops_requests (expires_at);

CREATE UNIQUE INDEX IF NOT EXISTS uq_endpoint_domain_ops_idempotency
    ON endpoint_domain_ops_requests (tenant_id, idempotency_key_hash)
    WHERE idempotency_key_hash IS NOT NULL;
