-- #802 slice 2: durable consent ownership/withdrawal state + service-local outbox.
-- Grant/revocation projections are append-only. The revoke projection is accepted
-- only when it matches the durable grant owner and canonical meeting scope.

CREATE TABLE recording_consent_grant (
    id                  UUID         PRIMARY KEY,
    event_key           VARCHAR(200) NOT NULL UNIQUE,
    source_hash         VARCHAR(64)  NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    meeting_id          UUID         NOT NULL,
    capture_id          UUID         NOT NULL UNIQUE,
    source_tenant_id    BIGINT       NOT NULL,
    tenant_id           UUID,
    org_id              UUID,
    actor_subject       VARCHAR(255) NOT NULL,
    actor_user_id       BIGINT       NOT NULL,
    consent_version     VARCHAR(64)  NOT NULL CHECK (consent_version ~ '^[A-Za-z0-9._:-]{1,64}$'),
    consent_text_hash   VARCHAR(71)  NOT NULL CHECK (consent_text_hash ~ '^sha256:[0-9a-f]{64}$'),
    locale              VARCHAR(10)  NOT NULL CHECK (locale ~ '^[a-z]{2}(-[A-Z]{2})?$'),
    consent_revision    BIGINT       NOT NULL CHECK (consent_revision = 1),
    correlation_id      VARCHAR(128),
    granted_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT recording_consent_grant_scope_pair
        CHECK ((tenant_id IS NULL) = (org_id IS NULL))
);

CREATE INDEX idx_recording_consent_grant_owner
    ON recording_consent_grant (source_tenant_id, actor_user_id, meeting_id);

CREATE OR REPLACE FUNCTION recording_consent_grant_append_only()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'recording_consent_grant is append-only: % rejected', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_recording_consent_grant_append_only
    BEFORE UPDATE OR DELETE ON recording_consent_grant
    FOR EACH ROW EXECUTE FUNCTION recording_consent_grant_append_only();

CREATE TABLE recording_consent_revocation (
    id                  UUID         PRIMARY KEY,
    event_key           VARCHAR(240) NOT NULL UNIQUE,
    source_hash         VARCHAR(64)  NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    meeting_id          UUID         NOT NULL,
    capture_id          UUID         NOT NULL,
    source_tenant_id    BIGINT       NOT NULL,
    tenant_id           UUID         NOT NULL,
    org_id              UUID         NOT NULL,
    actor_subject       VARCHAR(255) NOT NULL,
    actor_user_id       BIGINT       NOT NULL,
    consent_version     VARCHAR(64)  NOT NULL CHECK (consent_version ~ '^[A-Za-z0-9._:-]{1,64}$'),
    consent_revision    BIGINT       NOT NULL CHECK (consent_revision = 2),
    reason_code         VARCHAR(64)  NOT NULL CHECK (reason_code = 'USER_WITHDREW'),
    correlation_id      VARCHAR(128),
    revoked_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_recording_consent_revocation_occurrence
        UNIQUE (capture_id, consent_revision)
);

CREATE INDEX idx_recording_consent_revocation_meeting
    ON recording_consent_revocation (tenant_id, meeting_id, revoked_at DESC);

CREATE OR REPLACE FUNCTION recording_consent_revocation_append_only()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'recording_consent_revocation is append-only: % rejected', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_recording_consent_revocation_append_only
    BEFORE UPDATE OR DELETE ON recording_consent_revocation
    FOR EACH ROW EXECUTE FUNCTION recording_consent_revocation_append_only();

CREATE TABLE consent_event_outbox (
    id                  UUID         PRIMARY KEY,
    event_type          VARCHAR(64)  NOT NULL CHECK (event_type = 'meeting.consent.revoked'),
    event_key           VARCHAR(240) NOT NULL UNIQUE,
    aggregate_id        UUID         NOT NULL,
    meeting_id          UUID         NOT NULL,
    tenant_id           UUID         NOT NULL,
    org_id              UUID         NOT NULL,
    payload             TEXT         NOT NULL CHECK (length(payload) > 0),
    payload_hash        VARCHAR(64)  NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    claim_token         UUID,
    processing_owner    VARCHAR(128),
    claimed_at          TIMESTAMP(6) WITH TIME ZONE,
    lease_expires_at    TIMESTAMP(6) WITH TIME ZONE,
    attempts            INTEGER      NOT NULL DEFAULT 0,
    last_error          VARCHAR(255),
    next_attempt_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    created_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    published_at        TIMESTAMP(6) WITH TIME ZONE,
    updated_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT consent_event_outbox_status_known
        CHECK (status IN ('PENDING', 'CLAIMED', 'PUBLISHED', 'DEAD')),
    CONSTRAINT consent_event_outbox_attempts_nonnegative CHECK (attempts >= 0),
    CONSTRAINT consent_event_outbox_payload_hash_shape CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT consent_event_outbox_state_coherent CHECK (
        (status = 'PENDING' AND claim_token IS NULL AND processing_owner IS NULL
            AND claimed_at IS NULL AND lease_expires_at IS NULL AND published_at IS NULL)
        OR (status = 'CLAIMED' AND claim_token IS NOT NULL AND processing_owner IS NOT NULL
            AND claimed_at IS NOT NULL AND lease_expires_at IS NOT NULL AND published_at IS NULL)
        OR (status = 'PUBLISHED' AND claim_token IS NULL AND processing_owner IS NULL
            AND claimed_at IS NULL AND lease_expires_at IS NULL AND published_at IS NOT NULL)
        OR (status = 'DEAD' AND claim_token IS NULL AND processing_owner IS NULL
            AND claimed_at IS NULL AND lease_expires_at IS NULL AND published_at IS NULL)
    )
);

CREATE OR REPLACE FUNCTION consent_event_outbox_guard()
    RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'consent_event_outbox delete rejected'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF ROW(NEW.event_type, NEW.event_key, NEW.aggregate_id, NEW.meeting_id,
           NEW.tenant_id, NEW.org_id, NEW.payload, NEW.payload_hash, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.event_type, OLD.event_key, OLD.aggregate_id, OLD.meeting_id,
           OLD.tenant_id, OLD.org_id, OLD.payload, OLD.payload_hash, OLD.created_at) THEN
        RAISE EXCEPTION 'consent_event_outbox immutable payload/routing update rejected'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.attempts < OLD.attempts THEN
        RAISE EXCEPTION 'consent_event_outbox attempts cannot decrease'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'consent_event_outbox terminal row update rejected'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status = 'DEAD'
       AND NOT (COALESCE(current_setting('app.consent_outbox_redrive', true), '') = 'on'
                AND NEW.status = 'PENDING') THEN
        RAISE EXCEPTION 'consent_event_outbox DEAD row requires controlled redrive'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NOT ((OLD.status = 'PENDING' AND NEW.status = 'CLAIMED')
         OR (OLD.status = 'CLAIMED' AND NEW.status IN ('PENDING', 'PUBLISHED', 'DEAD'))
         OR (OLD.status = 'DEAD' AND NEW.status = 'PENDING'
             AND COALESCE(current_setting('app.consent_outbox_redrive', true), '') = 'on')) THEN
        RAISE EXCEPTION 'consent_event_outbox invalid state transition % -> %', OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_consent_event_outbox_guard
    BEFORE UPDATE OR DELETE ON consent_event_outbox
    FOR EACH ROW EXECUTE FUNCTION consent_event_outbox_guard();

-- Explicit, auditable operator recovery path for transport failures that
-- exhausted automatic retries. Attempts remain monotonic; immutable routing
-- and payload columns are untouched; the regular token-fenced poller performs
-- the next delivery. Direct DEAD -> PENDING updates remain rejected.
CREATE OR REPLACE FUNCTION consent_event_outbox_redrive(
        p_event_key VARCHAR,
        p_reason VARCHAR)
    RETURNS INTEGER AS $$
DECLARE
    updated_count INTEGER;
    safe_reason VARCHAR(200);
BEGIN
    safe_reason := COALESCE(
        NULLIF(regexp_replace(COALESCE(p_reason, ''), '[^A-Za-z0-9._:-]', '', 'g'), ''),
        'operator');
    PERFORM set_config('app.consent_outbox_redrive', 'on', true);
    UPDATE consent_event_outbox
       SET status = 'PENDING',
           claim_token = NULL,
           processing_owner = NULL,
           claimed_at = NULL,
           lease_expires_at = NULL,
           published_at = NULL,
           last_error = 'MANUAL_REDRIVE:' || left(safe_reason, 200),
           next_attempt_at = now(),
           updated_at = now(),
           version = version + 1
     WHERE event_key = p_event_key
       AND status = 'DEAD';
    GET DIAGNOSTICS updated_count = ROW_COUNT;
    RETURN updated_count;
END;
$$ LANGUAGE plpgsql SECURITY INVOKER;

CREATE INDEX idx_consent_event_outbox_pending
    ON consent_event_outbox (status, next_attempt_at, created_at, id);
CREATE INDEX idx_consent_event_outbox_aggregate
    ON consent_event_outbox (aggregate_id);
CREATE INDEX idx_consent_event_outbox_tenant_meeting
    ON consent_event_outbox (tenant_id, meeting_id);

COMMENT ON TABLE recording_consent_revocation IS
    '#802 durable append-only recorder consent withdrawal facts; contains no consent text, transcript, or audio.';
COMMENT ON TABLE recording_consent_grant IS
    '#802 durable append-only recorder consent ownership proof; stores only consent digest and controlled metadata.';
COMMENT ON TABLE consent_event_outbox IS
    '#802 service-local transactional outbox for meeting.consent.revoked; at-least-once publish, event_key consumer dedup; DEAD rows use consent_event_outbox_redrive.';
