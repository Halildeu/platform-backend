CREATE TABLE notify.native_push_registration (
    registration_id UUID PRIMARY KEY,
    org_id VARCHAR(64) NOT NULL,
    subscriber_id VARCHAR(128) NOT NULL,
    installation_id UUID NOT NULL,
    application_id VARCHAR(255) NOT NULL,
    provider VARCHAR(4) NOT NULL CHECK (provider IN ('FCM', 'APNS')),
    environment VARCHAR(10) NOT NULL CHECK (environment IN ('TEST', 'PRODUCTION')),
    token_hash CHAR(64) NOT NULL,
    token_ciphertext TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (application_id, provider, environment, installation_id),
    UNIQUE (application_id, provider, environment, token_hash)
);
CREATE INDEX native_push_owner_idx ON notify.native_push_registration(org_id, subscriber_id);
COMMENT ON TABLE notify.native_push_registration IS 'Native FCM/APNs registry. Logout deletes token material. Provider sending is a separate integration.';
