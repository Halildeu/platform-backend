-- Faz 23.2 PR-D.1 — audit partition cutover (Codex 019dfdec absorb).
--
-- Strategy A' (Codex iter-1 PARTIAL absorb):
-- - canonical table: notify.audit_event_v2 (PARTITIONED BY RANGE occurred_at)
-- - composite PK (id, occurred_at) — partition key dahil olmalı
-- - explicit sequence (SEQUENCE > BIGSERIAL — JPA @SequenceGenerator pattern)
-- - append-only TRIGGER (RULE DO INSTEAD NOTHING silent yerine; davranış testi yapılabilir)
-- - eski tablo: notify.audit_event_legacy (V8'de DROP edilmez — V9'da soak sonrası)
-- - compatibility surface: notify.audit_event view + INSTEAD OF INSERT trigger
--   (eski JPA contract'ını değiştirmeden canonical'a forward eder)
--
-- Initial partitions: retention horizon (90 gün geriye 2026_02..2026_07) + DEFAULT.
-- DEFAULT partition'a normal-ay verisi düşmemeli (Codex Q4 absorb); retention task
-- her run'da future partition ensure eder.

-- ============================================================================
-- 1) Sequence: explicit, owned by audit_event_v2.id
-- ============================================================================
CREATE SEQUENCE notify.audit_event_v2_id_seq AS BIGINT;

-- ============================================================================
-- 2) Canonical partitioned table
-- ============================================================================
CREATE TABLE notify.audit_event_v2 (
    id BIGINT NOT NULL DEFAULT nextval('notify.audit_event_v2_id_seq'),
    intent_id VARCHAR(64) NOT NULL,
    delivery_id BIGINT,
    event_type VARCHAR(64) NOT NULL,
    org_id VARCHAR(64) NOT NULL,
    topic_key VARCHAR(128) NOT NULL,
    recipient_hash VARCHAR(64),
    channel VARCHAR(32),
    template_id VARCHAR(128),
    template_version INT,
    details JSONB,
    correlation_id VARCHAR(128),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

ALTER SEQUENCE notify.audit_event_v2_id_seq
    OWNED BY notify.audit_event_v2.id;

-- ============================================================================
-- 3) Initial partitions (90-day retention horizon + future buffer + DEFAULT)
-- ============================================================================
-- Codex Q4 REVISE absorb: retention horizon (4 ay back) + current + 2 ay forward + DEFAULT.
-- Future partition idempotent ensure retention scheduled task'inde yapılır.
CREATE TABLE notify.audit_event_v2_2026_02 PARTITION OF notify.audit_event_v2
    FOR VALUES FROM ('2026-02-01 00:00:00+00') TO ('2026-03-01 00:00:00+00');
CREATE TABLE notify.audit_event_v2_2026_03 PARTITION OF notify.audit_event_v2
    FOR VALUES FROM ('2026-03-01 00:00:00+00') TO ('2026-04-01 00:00:00+00');
CREATE TABLE notify.audit_event_v2_2026_04 PARTITION OF notify.audit_event_v2
    FOR VALUES FROM ('2026-04-01 00:00:00+00') TO ('2026-05-01 00:00:00+00');
CREATE TABLE notify.audit_event_v2_2026_05 PARTITION OF notify.audit_event_v2
    FOR VALUES FROM ('2026-05-01 00:00:00+00') TO ('2026-06-01 00:00:00+00');
CREATE TABLE notify.audit_event_v2_2026_06 PARTITION OF notify.audit_event_v2
    FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');
CREATE TABLE notify.audit_event_v2_2026_07 PARTITION OF notify.audit_event_v2
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');
CREATE TABLE notify.audit_event_v2_default PARTITION OF notify.audit_event_v2 DEFAULT;

-- ============================================================================
-- 4) Indexes — query patterns: correlation_id, recipient_hash, intent_id+occurred_at
-- ============================================================================
CREATE INDEX idx_audit_event_v2_correlation_id
    ON notify.audit_event_v2 (correlation_id, occurred_at DESC);
CREATE INDEX idx_audit_event_v2_recipient_hash
    ON notify.audit_event_v2 (recipient_hash, occurred_at DESC)
    WHERE recipient_hash IS NOT NULL;
CREATE INDEX idx_audit_event_v2_intent_id
    ON notify.audit_event_v2 (intent_id, occurred_at DESC);
CREATE INDEX idx_audit_event_v2_event_type
    ON notify.audit_event_v2 (event_type, occurred_at DESC);

-- ============================================================================
-- 5) Append-only TRIGGER (Codex Q3 PARTIAL absorb — RULE silent vs explicit fail)
-- ============================================================================
-- TRIGGER raises EXCEPTION (vs RULE DO INSTEAD NOTHING silent) → behavior testable.
-- Postgres trigger inheritance: parent partitioned table TRIGGER otomatik tüm
-- partition'lara propagate olur (PG 11+).
CREATE OR REPLACE FUNCTION notify.audit_event_append_only_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only (% on % blocked)',
        TG_OP, TG_TABLE_NAME
        USING ERRCODE = '23514';  -- check_violation
END;
$$;

CREATE TRIGGER audit_event_v2_no_update
BEFORE UPDATE ON notify.audit_event_v2
FOR EACH ROW EXECUTE FUNCTION notify.audit_event_append_only_guard();

CREATE TRIGGER audit_event_v2_no_delete
BEFORE DELETE ON notify.audit_event_v2
FOR EACH ROW EXECUTE FUNCTION notify.audit_event_append_only_guard();

-- ============================================================================
-- 6) Data copy: V1 audit_event → audit_event_v2 (preservation guarantee)
-- ============================================================================
-- ACCESS EXCLUSIVE lock alır; mevcut V1 row count <1000 (test cluster) için
-- kabul edilebilir downtime (~ms-saniye). Prod row count yüksekse PR-D.1
-- öncesi pg_dump backup zorunlu.
INSERT INTO notify.audit_event_v2 (
    id, intent_id, delivery_id, event_type, org_id, topic_key,
    recipient_hash, channel, template_id, template_version,
    details, correlation_id, occurred_at
)
SELECT
    id, intent_id, delivery_id, event_type, org_id, topic_key,
    recipient_hash, channel, template_id, template_version,
    details, correlation_id, occurred_at
FROM notify.audit_event;

-- Sequence setval: copy sonrası max(id) + 1'den devam etsin
SELECT setval(
    'notify.audit_event_v2_id_seq',
    COALESCE((SELECT MAX(id) FROM notify.audit_event_v2), 0),
    true
);

-- ============================================================================
-- 7) V1 audit_event RULE'larını drop et (eski tabloyu legacy'ye çevirebilmek için)
-- ============================================================================
DROP RULE IF EXISTS audit_event_no_update ON notify.audit_event;
DROP RULE IF EXISTS audit_event_no_delete ON notify.audit_event;

-- ============================================================================
-- 8) Eski tabloyu legacy'ye taşı (V9'da soak sonrası DROP — rollback safety)
-- ============================================================================
ALTER TABLE notify.audit_event RENAME TO audit_event_legacy;

-- ============================================================================
-- 9) Compatibility view: notify.audit_event → audit_event_v2
-- ============================================================================
-- JPA mapping V8'de @Table("audit_event_v2") olacak; bu view eski cross-service
-- caller'lar (raw SQL) için backward-compat. INSTEAD OF INSERT trigger ile
-- INSERT'leri canonical'a forward eder.
CREATE VIEW notify.audit_event AS
    SELECT id, intent_id, delivery_id, event_type, org_id, topic_key,
           recipient_hash, channel, template_id, template_version,
           details, correlation_id, occurred_at
    FROM notify.audit_event_v2;

CREATE OR REPLACE FUNCTION notify.audit_event_view_insert_forward()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO notify.audit_event_v2 (
        id, intent_id, delivery_id, event_type, org_id, topic_key,
        recipient_hash, channel, template_id, template_version,
        details, correlation_id, occurred_at
    ) VALUES (
        COALESCE(NEW.id, nextval('notify.audit_event_v2_id_seq')),
        NEW.intent_id, NEW.delivery_id, NEW.event_type, NEW.org_id, NEW.topic_key,
        NEW.recipient_hash, NEW.channel, NEW.template_id, NEW.template_version,
        NEW.details, NEW.correlation_id, COALESCE(NEW.occurred_at, NOW())
    )
    RETURNING id, intent_id, delivery_id, event_type, org_id, topic_key,
              recipient_hash, channel, template_id, template_version,
              details, correlation_id, occurred_at
    INTO NEW.id, NEW.intent_id, NEW.delivery_id, NEW.event_type, NEW.org_id,
         NEW.topic_key, NEW.recipient_hash, NEW.channel, NEW.template_id,
         NEW.template_version, NEW.details, NEW.correlation_id, NEW.occurred_at;
    RETURN NEW;
END;
$$;

CREATE TRIGGER audit_event_view_insert_trigger
INSTEAD OF INSERT ON notify.audit_event
FOR EACH ROW EXECUTE FUNCTION notify.audit_event_view_insert_forward();

-- ============================================================================
-- 10) Audit retention metadata table
-- ============================================================================
-- Two-phase retention: DETACH PARTITION → audit_retention_log row → grace_hours
-- sonra DROP TABLE. Operatör log'tan dropped partition'ı izleyebilir.
CREATE TABLE notify.audit_retention_log (
    id BIGSERIAL PRIMARY KEY,
    partition_name VARCHAR(128) NOT NULL UNIQUE,
    range_start TIMESTAMPTZ NOT NULL,
    range_end TIMESTAMPTZ NOT NULL,
    detached_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    drop_after TIMESTAMPTZ NOT NULL,  -- detached_at + grace_hours
    dropped_at TIMESTAMPTZ,           -- NULL until drop step
    dry_run BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL DEFAULT 'detached',  -- detached | dropped | failed
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_retention_log_drop_after
    ON notify.audit_retention_log (drop_after)
    WHERE dropped_at IS NULL;

COMMENT ON TABLE notify.audit_retention_log IS
    'AuditPartitionRetentionService two-phase log: DETACH then (after grace) DROP. '
    'Operatör buradan dropped partition geçmişini izler. dropped_at IS NULL → '
    'henüz DROP edilmemiş (grace pencerede veya dry_run).';
