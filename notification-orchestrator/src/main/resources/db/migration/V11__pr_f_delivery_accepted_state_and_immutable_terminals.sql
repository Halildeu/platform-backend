-- Faz 23.4 PR-F — NotificationDelivery state machine hardening.
--
-- Two changes:
--   1. notification_delivery.status enum genişletme: ACCEPTED state eklendi
--      (provider kuyruğa aldı, DLR terminal sonucu bekleniyor — SMS adapters
--      için PR-F'in ana semantik ayrımı).
--   2. Forward-only state machine trigger: DB-level invariant guard.
--      Terminal status'lardan (DELIVERED/FAILED/BOUNCED/BLOCKED_*) hiçbir
--      transition kabul edilmez. ACCEPTED → DELIVERED/FAILED, RETRY → ACCEPTED,
--      PENDING → herhangi non-terminal transition allowed.
--
-- Codex iter-1 RED absorb (PR #85 thread 019e00d9):
--   - send=DELIVERED yanlış semantik (provider terminal authority DLR)
--   - DLR provider-side reject (17/70 IYS opt-out KVKK consent withdrawal)
--     hiç FAILED'a geçemiyordu → veri koruma kanunu açısından risk
--
-- Codex iter-2 PARTIAL absorb (plan-time consensus thread 019e00ec):
--   - BLOCKED_* statüleri de terminal — trigger bunları da immutable yapmalı
--   - Field cleanup terminal transition'larda gerek (failureReason / nextRetryAt
--     reset on RETRY → ACCEPTED; deliveredAt set on ACCEPTED → DELIVERED;
--     permanentFailureAt set + retry temizle on ACCEPTED → FAILED)

-- 1) Forward-only state machine + auto-cleanup trigger
CREATE OR REPLACE FUNCTION notify.notification_delivery_state_audit()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Insert: any valid status allowed (CHECK done by enum)
        RETURN NEW;
    END IF;

    -- Terminal state immutability (DLR / late event cannot mutate)
    IF OLD.status IN ('DELIVERED', 'FAILED', 'BOUNCED',
                      'BLOCKED_BY_PREFERENCE', 'BLOCKED_BY_AUTHZ',
                      'BLOCKED_BY_IDEMPOTENCY', 'BLOCKED_EXTERNAL_NOT_ALLOWED')
       AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION
            'notification_delivery: % is terminal; transition to % rejected',
            OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;

    -- Forward-only: explicit allowed transitions from non-terminal
    IF OLD.status = 'PENDING' AND NEW.status NOT IN
       ('PENDING', 'ACCEPTED', 'DELIVERED', 'FAILED', 'BOUNCED', 'RETRY',
        'BLOCKED_BY_PREFERENCE', 'BLOCKED_BY_AUTHZ',
        'BLOCKED_BY_IDEMPOTENCY', 'BLOCKED_EXTERNAL_NOT_ALLOWED') THEN
        RAISE EXCEPTION 'notification_delivery: PENDING -> % invalid', NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status = 'RETRY' AND NEW.status NOT IN
       ('RETRY', 'ACCEPTED', 'DELIVERED', 'FAILED', 'BOUNCED',
        'BLOCKED_BY_PREFERENCE', 'BLOCKED_BY_AUTHZ',
        'BLOCKED_BY_IDEMPOTENCY', 'BLOCKED_EXTERNAL_NOT_ALLOWED') THEN
        RAISE EXCEPTION 'notification_delivery: RETRY -> % invalid', NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status = 'ACCEPTED' AND NEW.status NOT IN
       ('ACCEPTED', 'DELIVERED', 'FAILED') THEN
        RAISE EXCEPTION 'notification_delivery: ACCEPTED -> % invalid', NEW.status
            USING ERRCODE = 'check_violation';
    END IF;

    -- Field cleanup on legal transitions (Codex iter-1 absorb):
    -- RETRY → ACCEPTED: worker re-dispatched; clear retry/lease state
    IF OLD.status = 'RETRY' AND NEW.status = 'ACCEPTED' THEN
        NEW.failure_reason = NULL;
        NEW.next_retry_at = NULL;
        NEW.processing_lease_until = NULL;
        NEW.claim_token = NULL;
    END IF;
    -- PENDING → ACCEPTED: first dispatch success; ensure no stale failure fields
    IF OLD.status = 'PENDING' AND NEW.status = 'ACCEPTED' THEN
        NEW.failure_reason = NULL;
    END IF;
    -- ACCEPTED → DELIVERED: DLR success; set delivered_at if not already set
    IF OLD.status = 'ACCEPTED' AND NEW.status = 'DELIVERED' THEN
        IF NEW.delivered_at IS NULL THEN
            NEW.delivered_at = NOW();
        END IF;
        -- ensure failure fields stay clean
        NEW.failure_reason = NULL;
        NEW.next_retry_at = NULL;
        NEW.processing_lease_until = NULL;
    END IF;
    -- ACCEPTED → FAILED: DLR carrier reject; set permanent_failure_at
    IF OLD.status = 'ACCEPTED' AND NEW.status = 'FAILED' THEN
        IF NEW.permanent_failure_at IS NULL THEN
            NEW.permanent_failure_at = NOW();
        END IF;
        NEW.next_retry_at = NULL;
        NEW.processing_lease_until = NULL;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_notification_delivery_state_audit
BEFORE INSERT OR UPDATE ON notify.notification_delivery
FOR EACH ROW EXECUTE FUNCTION notify.notification_delivery_state_audit();

-- Note: ACCEPTED enum value already valid since notification_delivery.status
-- column is VARCHAR (Hibernate @Enumerated(EnumType.STRING) maps via varchar
-- column, not native enum). No ALTER TYPE needed.

COMMENT ON FUNCTION notify.notification_delivery_state_audit() IS
    'Faz 23.4 PR-F: forward-only state machine + field cleanup. Terminal '
    'states (DELIVERED/FAILED/BOUNCED/BLOCKED_*) immutable. ACCEPTED state '
    'introduced for SMS provider-queued semantic; DLR terminalizes to '
    'DELIVERED or FAILED.';
