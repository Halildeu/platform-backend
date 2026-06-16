-- Faz 24 KVKK audit pipeline (gitops#1249) — audit_event immutable store.
--
-- The audit-event-consumer-service reads the audio-gateway `audit:events`
-- Redis stream with a consumer group and persists every event here. The table
-- is APPEND-ONLY and TAMPER-EVIDENT:
--   * insert-only (no application UPDATE/DELETE path);
--   * DB-level append-only enforced by trg_audit_event_append_only
--     (RAISEs on any UPDATE/DELETE — BE-016 endpoint_audit_events pattern reuse);
--   * a per-tenant SHA-256 hash-chain (prev_hash -> entry_hash) makes any
--     historical-row tamper detectable.
--
-- KVKK retention (m.12 audit-archive 7yr — #52 3-AI mutabakatının 7-yıl katmanı):
--   `event_timestamp` is the lawful-basis event time; `ingested_at` is the
--   persist time. The real 7-year archival/expiry worker is the #1250
--   follow-up (audit-retention-worker); this migration only fixes the schema +
--   immutability + chain so the retention worker has an authoritative,
--   tamper-evident source. NO row is ever deleted by this service.
--
-- Hash-chain (BE-016 reuse — endpoint-admin AuditChainSupport/AuditIntegrityVerifier):
--   entry_hash = SHA-256( DOMAIN_PREFIX \n "prev=" <prev|GENESIS> \n
--                         "payload=" <canonical-json(event fields)> )
--   First event per tenant: prev_hash = NULL (GENESIS). The hash columns
--   themselves are never part of the canonical payload.
--
-- Idempotency: `dedup_key` (natural key sessionId:chunkSeq:eventType for
--   ChunkAdmissionRejected) is UNIQUE so a restart / at-least-once redelivery
--   of the same stream entry cannot INSERT a duplicate row (ON CONFLICT DO
--   NOTHING in the consumer). `stream_entry_id` records the Redis entry id for
--   audit/forensics.

-- org_id compat (Faz 21.1 endpoint org_id pattern reuse): every tenant-scoped
-- row carries both tenant_id (NOT NULL) and org_id. A BEFORE INSERT/UPDATE
-- trigger backfills org_id from tenant_id for legacy writers, and a CHECK keeps
-- them consistent once both are present.

CREATE TABLE audit_event (
    -- BIGSERIAL hash-chain ordering anchor + monotonic ingest order.
    seq                  BIGSERIAL    PRIMARY KEY,
    id                   UUID         NOT NULL UNIQUE,

    tenant_id            UUID         NOT NULL,
    org_id               UUID,

    event_type           VARCHAR(100) NOT NULL,
    session_id           VARCHAR(128),
    user_id              BIGINT,
    chunk_seq            BIGINT,
    http_status          INTEGER,
    rejection_code       VARCHAR(100),
    retry_after_seconds  BIGINT,
    correlation_id       VARCHAR(128),

    -- Lawful-basis event time (producer timestampMs) + persist time.
    event_timestamp      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    ingested_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now(),

    -- Idempotency + forensics.
    dedup_key            VARCHAR(320) NOT NULL UNIQUE,
    stream_entry_id      VARCHAR(64),

    -- Hash-chain (BE-016 reuse).
    prev_hash            VARCHAR(64),
    entry_hash           VARCHAR(64)  NOT NULL,
    entry_hash_alg       VARCHAR(32)  NOT NULL,
    entry_hash_version   INTEGER      NOT NULL,

    CONSTRAINT chk_audit_event_org_id_consistent
        CHECK (org_id IS NULL OR tenant_id IS NULL OR org_id = tenant_id)
);

COMMENT ON TABLE audit_event IS
    'Faz 24 KVKK audit pipeline (gitops#1249): immutable, tenant-scoped, hash-chained audit events consumed from the audio-gateway audit:events Redis stream. Append-only; 7yr retention archival is #1250 follow-up.';
COMMENT ON COLUMN audit_event.seq IS
    'Monotonic ingest/hash-chain ordering anchor (BIGSERIAL).';
COMMENT ON COLUMN audit_event.dedup_key IS
    'Natural-key idempotency token (e.g. sessionId:chunkSeq:eventType). UNIQUE — at-least-once redelivery does not duplicate.';
COMMENT ON COLUMN audit_event.prev_hash IS
    'entry_hash of the previous event in the same tenant chain; NULL = tenant GENESIS.';
COMMENT ON COLUMN audit_event.entry_hash IS
    'lowercase hex SHA-256 over canonical event payload + prev hash (BE-016 chain).';
COMMENT ON COLUMN audit_event.event_timestamp IS
    'Lawful-basis event time (producer-supplied). KVKK m.12 retention anchor.';

-- Per-tenant chain-tail lookup: latest row for a tenant by seq.
CREATE INDEX idx_audit_event_tenant_seq        ON audit_event (tenant_id, seq DESC);
CREATE INDEX idx_audit_event_event_timestamp   ON audit_event (event_timestamp);
CREATE INDEX idx_audit_event_org_id            ON audit_event (org_id);
CREATE INDEX idx_audit_event_event_type        ON audit_event (event_type);

-- ---------------------------------------------------------------------------
-- org_id compat trigger (Faz 21.1 reuse): fill org_id from tenant_id when the
-- writer leaves it null. Side-effect-free for writers that supply org_id.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION audit_event_org_id_compat_fill()
    RETURNS TRIGGER AS $$
BEGIN
    IF NEW.org_id IS NULL AND NEW.tenant_id IS NOT NULL THEN
        NEW.org_id := NEW.tenant_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_event_org_id_compat
    BEFORE INSERT OR UPDATE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_org_id_compat_fill();

-- ---------------------------------------------------------------------------
-- Append-only enforcement (BE-016 reuse): reject ANY direct UPDATE/DELETE at
-- the database layer. INSERT (the only legitimate audit write path) stays
-- allowed. Combined with the application-side insert-only repository + the
-- hash-chain, this yields genuine tamper-evidence.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION audit_event_append_only()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'audit_event is append-only (Faz 24 KVKK audit integrity): % rejected', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_append_only();

-- ---------------------------------------------------------------------------
-- Insert enforcement: every inserted row MUST carry the hash-chain columns, so
-- a direct SQL insert cannot create an unhashed row the verifier would skip.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION audit_event_require_hash()
    RETURNS TRIGGER AS $$
BEGIN
    IF NEW.entry_hash IS NULL
        OR NEW.entry_hash_alg IS NULL
        OR NEW.entry_hash_version IS NULL THEN
        RAISE EXCEPTION
            'audit_event insert requires entry_hash + entry_hash_alg + entry_hash_version (Faz 24 KVKK audit integrity)'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_event_require_hash
    BEFORE INSERT ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_require_hash();
