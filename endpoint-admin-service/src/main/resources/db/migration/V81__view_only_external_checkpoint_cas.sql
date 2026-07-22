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
    CONSTRAINT ck_vo_lease_closed_consistency CHECK (NOT closed OR write_count > 0),
    CONSTRAINT uq_vo_lease_authority_projection UNIQUE (
        lease_id, transaction_id_sha256, binding_sha256, lease_envelope_sha256,
        expires_at, executor_identity_sha256)
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
    CONSTRAINT fk_vo_checkpoint_exact_lease_authority FOREIGN KEY (
        lease_id, transaction_id_sha256, binding_sha256, lease_envelope_sha256,
        expires_at, executor_identity_sha256)
        REFERENCES view_only_checkpoint_leases (
            lease_id, transaction_id_sha256, binding_sha256, lease_envelope_sha256,
            expires_at, executor_identity_sha256),
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

-- The application owner may legitimately bind one executor and monotonically
-- consume the lease, but every authority field is immutable. This is enforced
-- in PostgreSQL so another application code path cannot rewrite signed truth.
CREATE OR REPLACE FUNCTION view_only_lease_guard() RETURNS trigger AS $$
DECLARE
    bind_only boolean;
    consume_only boolean;
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'view_only_checkpoint_leases is durable authority: % is not permitted', TG_OP;
    END IF;

    IF NEW.lease_id IS DISTINCT FROM OLD.lease_id
        OR NEW.redeem_request_id IS DISTINCT FROM OLD.redeem_request_id
        OR NEW.idempotency_key_sha256 IS DISTINCT FROM OLD.idempotency_key_sha256
        OR NEW.request_body_sha256 IS DISTINCT FROM OLD.request_body_sha256
        OR NEW.authorization_caller_identity_sha256 IS DISTINCT FROM OLD.authorization_caller_identity_sha256
        OR NEW.transaction_id_sha256 IS DISTINCT FROM OLD.transaction_id_sha256
        OR NEW.binding_sha256 IS DISTINCT FROM OLD.binding_sha256
        OR NEW.binding_canonical_json IS DISTINCT FROM OLD.binding_canonical_json
        OR NEW.lease_envelope_sha256 IS DISTINCT FROM OLD.lease_envelope_sha256
        OR NEW.evaluation_preflight_envelope_sha256 IS DISTINCT FROM OLD.evaluation_preflight_envelope_sha256
        OR NEW.redemption_preflight_envelope_sha256 IS DISTINCT FROM OLD.redemption_preflight_envelope_sha256
        OR NEW.authorization_envelope_sha256 IS DISTINCT FROM OLD.authorization_envelope_sha256
        OR NEW.signed_lease_envelope IS DISTINCT FROM OLD.signed_lease_envelope
        OR NEW.issued_at IS DISTINCT FROM OLD.issued_at
        OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
        OR NEW.max_writes IS DISTINCT FROM OLD.max_writes
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'view_only_checkpoint_leases authority columns are immutable';
    END IF;

    bind_only := OLD.executor_identity_sha256 IS NULL
        AND NEW.executor_identity_sha256 IS NOT NULL
        AND NEW.write_count = OLD.write_count
        AND NEW.closed = OLD.closed;
    consume_only := OLD.executor_identity_sha256 IS NOT NULL
        AND NEW.executor_identity_sha256 = OLD.executor_identity_sha256
        AND NOT OLD.closed
        AND NEW.write_count = OLD.write_count + 1
        AND (NEW.closed = OLD.closed OR NEW.closed);
    IF NOT bind_only AND NOT consume_only THEN
        RAISE EXCEPTION 'view_only_checkpoint_leases permits only one executor bind or one monotonic consume';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_vo_lease_guard
    BEFORE UPDATE OR DELETE ON view_only_checkpoint_leases
    FOR EACH ROW EXECUTE FUNCTION view_only_lease_guard();

CREATE TRIGGER trg_vo_lease_no_truncate
    BEFORE TRUNCATE ON view_only_checkpoint_leases
    FOR EACH STATEMENT EXECUTE FUNCTION view_only_lease_guard();

-- External checkpoint evidence is WORM. Its sequence/state/digest projection
-- is used for later authorization decisions, so mutation is never a convention.
CREATE OR REPLACE FUNCTION view_only_checkpoint_no_mutate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'view_only_external_checkpoints is append-only: % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_vo_checkpoint_worm
    BEFORE UPDATE OR DELETE ON view_only_external_checkpoints
    FOR EACH ROW EXECUTE FUNCTION view_only_checkpoint_no_mutate();

CREATE TRIGGER trg_vo_checkpoint_no_truncate
    BEFORE TRUNCATE ON view_only_external_checkpoints
    FOR EACH STATEMENT EXECUTE FUNCTION view_only_checkpoint_no_mutate();

REVOKE UPDATE, DELETE, TRUNCATE ON view_only_external_checkpoints FROM PUBLIC;
REVOKE DELETE, TRUNCATE ON view_only_checkpoint_leases FROM PUBLIC;
