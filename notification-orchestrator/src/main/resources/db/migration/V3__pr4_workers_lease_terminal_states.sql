-- Faz 23.1 PR4 — workers + DLQ + terminal states (Codex 019dfa47 plan-time absorb).
--
-- Bu migration:
--   1. Intent lease columns (worker claim crash recovery — Codex Q6 absorb)
--   2. Delivery lease column (RetryWorker claim crash recovery)
--   3. Terminal status enum genişlemesi (Codex Q4 REVISE absorb): FAILED + PARTIALLY_FAILED
--      app-level; DB CHECK yok (NotificationIntent.Status enum @Enumerated(STRING))
--   4. DLQ unique index (replayed = FALSE — duplicate active DLQ engelle)
--   5. notification_intent.terminated_at (terminal transition timestamp)
--   6. notification_delivery.permanent_failure_at (FAILED/BOUNCED transition timestamp)

-- ============================================================================
-- 1) Intent lease (worker claim crash recovery)
-- ============================================================================
-- Worker pod claim sonrası crash ederse, intent sonsuza kadar PROCESSING
-- kalmasin. processing_lease_until geçmiş olan PROCESSING intent'leri
-- OutboxPoller lease-recovery cycle'ında PENDING'e geri çevirir.
ALTER TABLE notify.notification_intent
    ADD COLUMN processing_started_at TIMESTAMPTZ,
    ADD COLUMN processing_lease_until TIMESTAMPTZ,
    ADD COLUMN processing_owner VARCHAR(64),
    ADD COLUMN terminated_at TIMESTAMPTZ;

COMMENT ON COLUMN notify.notification_intent.processing_lease_until IS
    'Worker lease deadline (Codex 019dfa47 Q6 absorb). NULL veya geçmiş = '
    'reclaim-eligible. OutboxPoller her cycle stale lease intent''lerini '
    'PENDING''e revert eder.';

CREATE INDEX idx_intent_lease_recovery
    ON notify.notification_intent (status, processing_lease_until)
    WHERE status = 'PROCESSING';

-- ============================================================================
-- 2) Delivery lease (RetryWorker crash recovery)
-- ============================================================================
ALTER TABLE notify.notification_delivery
    ADD COLUMN processing_lease_until TIMESTAMPTZ,
    ADD COLUMN permanent_failure_at TIMESTAMPTZ;

COMMENT ON COLUMN notify.notification_delivery.processing_lease_until IS
    'RetryWorker lease deadline. Stale lease delivery RETRY status korur ama '
    'reclaim-eligible olur.';

-- ============================================================================
-- 3) DLQ active uniqueness (Codex Q5 absorb — replayed=FALSE duplicate engelle)
-- ============================================================================
-- payload_snapshot column'u DeadLetter entity'de yok; mevcut V1 schema yeterli.
-- Aktif DLQ row başına 1 delivery — replayed sonrası yeni DLQ giriş yapılabilir.
CREATE UNIQUE INDEX uq_dead_letter_active_delivery
    ON notify.dead_letter (delivery_id)
    WHERE replayed = FALSE;

-- ============================================================================
-- 4) Worker query indexes (Codex Q1 atomic claim performans)
-- ============================================================================
-- OutboxPoller native claim query: PENDING + due (scheduled_at IS NULL OR <= now)
-- + (expire_at IS NULL OR > now). Mevcut idx_intent_status_scheduled yeterli
-- ama aktif partial index optimize.
CREATE INDEX idx_intent_pending_due
    ON notify.notification_intent (scheduled_at, created_at, id)
    WHERE status = 'PENDING';

-- RetryWorker native claim query: status=RETRY + next_retry_at <= now
-- Mevcut idx_delivery_status_retry hâlihazır var; ekstra index gerekmez.

-- ============================================================================
-- 5) DLQ retention index (PR4 metric/ops query)
-- ============================================================================
CREATE INDEX idx_dead_letter_unreplayed
    ON notify.dead_letter (moved_to_dlq_at)
    WHERE replayed = FALSE;
