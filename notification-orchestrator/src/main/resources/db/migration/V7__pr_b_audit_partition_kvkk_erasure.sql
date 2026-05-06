-- Faz 23.2 PR-B — audit partition + KVKK erasure foundation (Codex 019dfae5
-- Q1 PARTIAL A-revised + Q2 PARTIAL absorb).
--
-- Bu migration:
--   1. audit_event tablosunu PARTITION BY RANGE (occurred_at) parent'a çevirir
--      (mevcut data → default partition; future writes month partitions)
--   2. Append-only RULE'ler parent'a taşınır (DDL DETACH/DROP partition retention
--      ile uyumlu; row-level DELETE hâlâ NOTHING)
--   3. notification_intent: payload + recipients_snapshot + metadata nullable
--      (KVKK erasure için PII purge yapılabilir)
--   4. Initial partitions: current month + next month + default (catch-all)
--   5. Partition management runbook (operator/PR-C scheduled task ile devam)

-- ============================================================================
-- 1) audit_event partition migration (Codex 019dfae5 Q1 A-revised absorb)
-- ============================================================================
-- Strategy: rename + recreate parent + reattach
--   a) Existing audit_event → audit_event_legacy
--   b) Recreate audit_event as PARTITION BY RANGE (occurred_at)
--   c) Attach legacy as default partition (no-bound; PG 11+ supports default)
--   d) Append-only RULE'ler parent'a uygulanır (DETACH/DROP DDL retention OK)
--
-- NOTE: Partition key (occurred_at) PRIMARY KEY'in parçası olmalı PG kuralı
-- gereği. Mevcut PK = id. Çözüm: parent'ta PK (id, occurred_at) — composite.

-- a) Rename existing → legacy (data preserved)
ALTER TABLE notify.audit_event RENAME TO audit_event_legacy;

-- Drop old append-only rules (will reapply on parent)
DROP RULE IF EXISTS audit_event_no_update ON notify.audit_event_legacy;
DROP RULE IF EXISTS audit_event_no_delete ON notify.audit_event_legacy;

-- b) Recreate parent as partitioned table (composite PK with occurred_at)
CREATE TABLE notify.audit_event (
    id BIGSERIAL,
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

-- c) Attach legacy as default partition (catch existing rows + edge dates)
-- NOTE: Default partition: tüm range outside explicit bounds buraya gider.
-- Operator runbook: legacy partition'daki eski data scheduled archive sonrası
-- DETACH + DROP yapılır.
ALTER TABLE notify.audit_event_legacy
    ADD CONSTRAINT chk_legacy_partition_compat CHECK (occurred_at IS NOT NULL);

ALTER TABLE notify.audit_event ATTACH PARTITION notify.audit_event_legacy DEFAULT;

-- d) Append-only RULE'ler parent'a (Codex iter-2 absorb: rule INSTEAD NOTHING
-- DDL retention DETACH/DROP'a engel değil — sadece row-level DML engelliyor)
CREATE OR REPLACE RULE audit_event_no_update AS ON UPDATE TO notify.audit_event
    DO INSTEAD NOTHING;
CREATE OR REPLACE RULE audit_event_no_delete AS ON DELETE TO notify.audit_event
    DO INSTEAD NOTHING;

-- e) Initial month partitions (current + next; operator/scheduled task tatmin)
-- Format: audit_event_YYYY_MM, range FROM (start of month) TO (start of next month)
-- 2026-05 (current) — bu migration tarihinden itibaren explicit partition
CREATE TABLE notify.audit_event_2026_05 PARTITION OF notify.audit_event
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');

CREATE TABLE notify.audit_event_2026_06 PARTITION OF notify.audit_event
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');

-- Index per partition (parent indexes propagate to child partitions in PG 11+)
CREATE INDEX idx_audit_event_correlation_id ON notify.audit_event (correlation_id);
CREATE INDEX idx_audit_event_intent_id ON notify.audit_event (intent_id);
CREATE INDEX idx_audit_event_recipient_hash ON notify.audit_event (recipient_hash)
    WHERE recipient_hash IS NOT NULL;
CREATE INDEX idx_audit_event_event_type ON notify.audit_event (event_type, occurred_at);

COMMENT ON TABLE notify.audit_event IS
    'Audit event partition parent (Codex 019dfae5 PR-B Q1 A-revised absorb). '
    'Partition by RANGE (occurred_at) month; append-only RULE; retention via '
    'DETACH/DROP PARTITION DDL (row DELETE NOTHING). Operator runbook: '
    'docs/runbooks/RB-faz-23-2-audit-partition-management.md (PR-C).';

-- ============================================================================
-- 2) notification_intent — KVKK erasure: PII fields nullable
-- ============================================================================
-- Codex Q2 PARTIAL absorb: erasure sadece payload değil; recipients_snapshot,
-- metadata, channel_routing, preference_override içindeki PII de purge'lenmeli.
-- Bu migration: tümünü nullable yapar (önceki NOT NULL constraint'leri).

ALTER TABLE notify.notification_intent
    ALTER COLUMN payload DROP NOT NULL;

-- recipients_snapshot zaten nullable (V2'de eklendi)
-- metadata zaten nullable
-- channel_routing zaten nullable
-- preference_override zaten nullable

COMMENT ON COLUMN notify.notification_intent.payload IS
    'Intent payload (KVKK erasure: nullable; ErasureService.eraseSubscriber() '
    'sets to NULL after audit append SUBSCRIBER_ERASURE_REQUEST).';
