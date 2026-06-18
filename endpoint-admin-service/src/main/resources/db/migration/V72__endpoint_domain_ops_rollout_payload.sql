ALTER TABLE endpoint_domain_ops_requests
    ADD COLUMN IF NOT EXISTS operation_payload JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE endpoint_domain_ops_requests
    DROP CONSTRAINT IF EXISTS ck_endpoint_domain_ops_operation;

ALTER TABLE endpoint_domain_ops_requests
    ADD CONSTRAINT ck_endpoint_domain_ops_operation CHECK (
        operation IN (
            'DOMAIN_SECURE_CHANNEL_VERIFY',
            'GPO_FORCE_REFRESH',
            'CERT_AUTOENROLL_PULSE',
            'ENDPOINT_AGENT_GPO_MSI_DEPLOYMENT',
            'ENDPOINT_AGENT_ROLLOUT_COLLECTOR'
        )
    );

ALTER TABLE endpoint_domain_ops_requests
    ADD CONSTRAINT ck_endpoint_domain_ops_operation_payload_shape
    CHECK (jsonb_typeof(operation_payload) = 'object');
