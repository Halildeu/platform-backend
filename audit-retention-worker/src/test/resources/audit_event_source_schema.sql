-- Test-only: recreate the consumer-owned source schema (audit_event.audit_event
-- + append-only / require-hash triggers) so the worker reads a REAL,
-- trigger-protected source under Testcontainers. Mirrors
-- audit-event-consumer-service V1__audit_event_baseline.sql (platform-backend@74c9e1a9).
-- Fully schema-qualified + executed as ONE multi-statement script (the $$ bodies
-- must NOT be split on ';').
CREATE SCHEMA IF NOT EXISTS audit_event;

CREATE TABLE audit_event.audit_event (
    seq                  BIGSERIAL    PRIMARY KEY,
    id                   UUID         NOT NULL UNIQUE,
    tenant_id            BIGINT       NOT NULL,
    event_type           VARCHAR(100) NOT NULL,
    session_id           VARCHAR(128),
    user_id              BIGINT,
    chunk_seq            BIGINT,
    http_status          INTEGER,
    rejection_code       VARCHAR(100),
    retry_after_seconds  BIGINT,
    correlation_id       VARCHAR(128),
    event_timestamp      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    ingested_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),
    dedup_key            VARCHAR(320) NOT NULL UNIQUE,
    stream_entry_id      VARCHAR(64),
    prev_hash            VARCHAR(64),
    entry_hash           VARCHAR(64)  NOT NULL,
    entry_hash_alg       VARCHAR(32)  NOT NULL,
    entry_hash_version   INTEGER      NOT NULL
);

CREATE INDEX idx_audit_event_tenant_seq       ON audit_event.audit_event (tenant_id, seq DESC);
CREATE INDEX idx_audit_event_tenant_timestamp ON audit_event.audit_event (tenant_id, event_timestamp);

CREATE OR REPLACE FUNCTION audit_event.audit_event_append_only()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'audit_event is append-only (Faz 24 KVKK audit integrity): % rejected', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit_event.audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event.audit_event_append_only();

CREATE OR REPLACE FUNCTION audit_event.audit_event_require_hash()
    RETURNS TRIGGER AS $$
BEGIN
    IF NEW.entry_hash IS NULL
        OR NEW.entry_hash_alg IS NULL
        OR NEW.entry_hash_version IS NULL THEN
        RAISE EXCEPTION
            'audit_event insert requires entry_hash + entry_hash_alg + entry_hash_version'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_event_require_hash
    BEFORE INSERT ON audit_event.audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event.audit_event_require_hash();
