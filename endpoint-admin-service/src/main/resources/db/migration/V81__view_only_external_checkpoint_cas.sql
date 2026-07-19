-- Faz 22.6 #891 -- durable, lease-bound VIEW_ONLY external checkpoint CAS.
--
-- Raw OIDC tokens, product credentials, refresh tokens, passwords, private
-- keys, consent decisions and screen content are deliberately absent. Only
-- signed public envelopes and content-addressed digests cross this boundary.

CREATE TABLE view_only_checkpoint_leases (
    lease_id                                  UUID         PRIMARY KEY,
    redeem_request_id                         UUID         NOT NULL UNIQUE,
    idempotency_key_sha256                    VARCHAR(71)  NOT NULL UNIQUE,
    request_body_sha256                       VARCHAR(71)  NOT NULL,
    authorization_caller_identity_sha256      VARCHAR(71)  NOT NULL,
    transaction_id_sha256                     VARCHAR(71)  NOT NULL UNIQUE,
    binding_sha256                            VARCHAR(71)  NOT NULL,
    binding_canonical_json                    TEXT         NOT NULL,
    lease_envelope_sha256                     VARCHAR(71)  NOT NULL UNIQUE,
    evaluation_preflight_envelope_sha256      VARCHAR(71)  NOT NULL,
    redemption_preflight_envelope_sha256      VARCHAR(71)  NOT NULL,
    authorization_envelope_sha256             VARCHAR(71)  NOT NULL UNIQUE,
    signed_lease_envelope                     BYTEA        NOT NULL,
    issued_at                                 TIMESTAMPTZ  NOT NULL,
    expires_at                                TIMESTAMPTZ  NOT NULL,
    max_writes                                SMALLINT     NOT NULL,
    write_count                               SMALLINT     NOT NULL DEFAULT 0,
    closed                                    BOOLEAN      NOT NULL DEFAULT FALSE,
    executor_identity_sha256                  VARCHAR(71),
    created_at                                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_vo_lease_digest_format CHECK (
        idempotency_key_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND request_body_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND authorization_caller_identity_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND transaction_id_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND binding_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND lease_envelope_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND evaluation_preflight_envelope_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND redemption_preflight_envelope_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND authorization_envelope_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND (executor_identity_sha256 IS NULL OR executor_identity_sha256 ~ '^sha256:[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_vo_lease_binding_nonempty CHECK (length(binding_canonical_json) > 2),
    CONSTRAINT ck_vo_lease_response_bound CHECK (octet_length(signed_lease_envelope) BETWEEN 1 AND 1048576),
    CONSTRAINT ck_vo_lease_lifetime CHECK (issued_at < expires_at),
    CONSTRAINT ck_vo_lease_write_bound CHECK (max_writes = 64 AND write_count BETWEEN 0 AND max_writes),
    CONSTRAINT ck_vo_lease_closed_consistency CHECK (NOT closed OR write_count > 0)
);

CREATE INDEX idx_vo_checkpoint_lease_expiry
    ON view_only_checkpoint_leases (expires_at)
    WHERE closed = FALSE;

CREATE TABLE view_only_external_checkpoints (
    transaction_id_sha256                 VARCHAR(71)  NOT NULL,
    sequence                              SMALLINT     NOT NULL,
    lease_id                              UUID         NOT NULL,
    request_id                            UUID         NOT NULL UNIQUE,
    idempotency_key_sha256                VARCHAR(71)  NOT NULL UNIQUE,
    request_body_sha256                   VARCHAR(71)  NOT NULL,
    executor_identity_sha256              VARCHAR(71)  NOT NULL,
    lease_envelope_sha256                 VARCHAR(71)  NOT NULL,
    binding_sha256                        VARCHAR(71)  NOT NULL,
    previous_state                        VARCHAR(32),
    state                                 VARCHAR(32)  NOT NULL,
    reason_code                           VARCHAR(64)  NOT NULL,
    local_checkpoint_sha256               VARCHAR(71)  NOT NULL,
    local_payload_sha256                  VARCHAR(71)  NOT NULL,
    previous_stored_object_sha256         VARCHAR(71),
    stored_object_sha256                  VARCHAR(71)  NOT NULL UNIQUE,
    signed_receipt_envelope               BYTEA        NOT NULL,
    terminal                              BOOLEAN      NOT NULL,
    created_at                            TIMESTAMPTZ  NOT NULL,
    expires_at                            TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (transaction_id_sha256, sequence),
    CONSTRAINT fk_vo_checkpoint_lease FOREIGN KEY (lease_id)
        REFERENCES view_only_checkpoint_leases(lease_id),
    CONSTRAINT ck_vo_checkpoint_sequence CHECK (sequence BETWEEN 0 AND 63),
    CONSTRAINT ck_vo_checkpoint_digest_format CHECK (
        transaction_id_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND idempotency_key_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND request_body_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND executor_identity_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND lease_envelope_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND binding_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND local_checkpoint_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND local_payload_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND (previous_stored_object_sha256 IS NULL OR previous_stored_object_sha256 ~ '^sha256:[0-9a-f]{64}$')
        AND stored_object_sha256 ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_vo_checkpoint_state CHECK (state IN (
        'DECISION_AUTHORIZED', 'LIVE_REVALIDATED', 'ACTIVATED', 'CONSENT_PENDING',
        'EVIDENCE_COLLECTED', 'EVIDENCE_VERIFIED', 'FAILURE_CAPTURED',
        'ARTIFACTS_STAGED', 'ARTIFACTS_STAGE_FAILED', 'ROLLBACK_PENDING',
        'ROLLED_BACK', 'COMPLETED', 'FAILED_CLEAN')),
    CONSTRAINT ck_vo_checkpoint_previous_state CHECK (previous_state IS NULL OR previous_state IN (
        'DECISION_AUTHORIZED', 'LIVE_REVALIDATED', 'ACTIVATED', 'CONSENT_PENDING',
        'EVIDENCE_COLLECTED', 'EVIDENCE_VERIFIED', 'FAILURE_CAPTURED',
        'ARTIFACTS_STAGED', 'ARTIFACTS_STAGE_FAILED', 'ROLLBACK_PENDING',
        'ROLLED_BACK', 'COMPLETED', 'FAILED_CLEAN')),
    CONSTRAINT ck_vo_checkpoint_initial_shape CHECK (
        (sequence = 0 AND previous_state IS NULL AND previous_stored_object_sha256 IS NULL
            AND state = 'DECISION_AUTHORIZED')
        OR
        (sequence > 0 AND previous_state IS NOT NULL AND previous_stored_object_sha256 IS NOT NULL)
    ),
    CONSTRAINT ck_vo_checkpoint_terminal_shape CHECK (
        terminal = (state IN ('COMPLETED', 'FAILED_CLEAN'))
    ),
    CONSTRAINT ck_vo_checkpoint_reason CHECK (reason_code ~ '^[a-z0-9][a-z0-9-]{1,63}$'),
    CONSTRAINT ck_vo_checkpoint_response_bound CHECK (octet_length(signed_receipt_envelope) BETWEEN 1 AND 524288),
    CONSTRAINT ck_vo_checkpoint_lifetime CHECK (created_at < expires_at)
);

CREATE INDEX idx_vo_checkpoint_lease_sequence
    ON view_only_external_checkpoints (lease_id, sequence DESC);
