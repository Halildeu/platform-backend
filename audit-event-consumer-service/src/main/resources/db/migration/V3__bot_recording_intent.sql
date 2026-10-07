-- Separate synchronous bot authority; legacy Redis grants/revocations cannot admit a bot.
CREATE TABLE bot_recording_intent (
    id UUID PRIMARY KEY,
    company_id BIGINT NOT NULL CHECK (company_id > 0),
    request_key UUID NOT NULL,
    owner_issuer VARCHAR(512) NOT NULL,
    owner_subject VARCHAR(255) NOT NULL,
    grant_json TEXT NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL,
    expires_at TIMESTAMPTZ(6) NOT NULL CHECK (expires_at > created_at),
    state VARCHAR(16) NOT NULL CHECK (state IN ('GRANTED', 'BOUND', 'REVOKED')),
    revision BIGINT NOT NULL CHECK (revision BETWEEN 1 AND 3),
    binding_json TEXT,
    revoked_at TIMESTAMPTZ(6),
    UNIQUE (company_id, owner_issuer, owner_subject, request_key),
    CHECK ((state = 'GRANTED' AND revision = 1 AND binding_json IS NULL AND revoked_at IS NULL)
        OR (state = 'BOUND' AND revision = 2 AND binding_json IS NOT NULL AND revoked_at IS NULL)
        OR (state = 'REVOKED' AND revoked_at IS NOT NULL
            AND ((revision = 2 AND binding_json IS NULL) OR (revision = 3 AND binding_json IS NOT NULL))))
);

CREATE TABLE bot_recording_evidence (
    id UUID PRIMARY KEY REFERENCES audit_event(id),
    intent_id UUID NOT NULL REFERENCES bot_recording_intent(id),
    revision BIGINT NOT NULL,
    snapshot_json TEXT NOT NULL,
    snapshot_hash VARCHAR(64) NOT NULL CHECK (snapshot_hash ~ '^[a-f0-9]{64}$'),
    UNIQUE (intent_id, revision)
);
CREATE TRIGGER trg_bot_recording_evidence_append_only
    BEFORE UPDATE OR DELETE ON bot_recording_evidence
    FOR EACH ROW EXECUTE FUNCTION audit_event_append_only();

CREATE FUNCTION bot_recording_intent_transition() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'bot recording intent deletion is forbidden' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF (NEW.id, NEW.company_id, NEW.request_key, NEW.owner_issuer, NEW.owner_subject,
            NEW.grant_json, NEW.created_at, NEW.expires_at)
        IS DISTINCT FROM
       (OLD.id, OLD.company_id, OLD.request_key, OLD.owner_issuer, OLD.owner_subject,
            OLD.grant_json, OLD.created_at, OLD.expires_at)
        OR OLD.state = 'REVOKED' OR NEW.revision <> OLD.revision + 1
        OR NOT ((OLD.state = 'GRANTED' AND NEW.state IN ('BOUND', 'REVOKED'))
            OR (OLD.state = 'BOUND' AND NEW.state = 'REVOKED'))
        OR (OLD.binding_json IS NOT NULL AND NEW.binding_json IS DISTINCT FROM OLD.binding_json) THEN
        RAISE EXCEPTION 'invalid bot recording intent transition' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_bot_recording_intent_transition BEFORE UPDATE OR DELETE ON bot_recording_intent
    FOR EACH ROW EXECUTE FUNCTION bot_recording_intent_transition();
