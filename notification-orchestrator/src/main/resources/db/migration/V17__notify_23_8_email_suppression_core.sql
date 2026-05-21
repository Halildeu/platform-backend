-- Faz 23.8 M7 T4.3.b — Email suppression core (Codex 019e492f AGREE
-- iter-1 partial MVP scope: provider/reputation guard for SMTP path;
-- DSN poll worker + webhook adapter ayrı slice).
--
-- Problem: SMTP `250 OK` mailbox-edge kabulüdür, recipient inbox-level
-- bounce kanıtı değildir. Hard-bounce alıcılar suppression list'e
-- gitmeyince Office 365 IP reputation kayıpla sonuçlanır. Bu migration
-- core data store + delivery status enum genişletme yapar; admin API +
-- DeliveryEligibilityService integration + future DSN/webhook ingest
-- aynı PR'da gelir.
--
-- Schema design (Codex 019e492f Q2 absorb):
--   - Table adı `email_suppression` (subscriber-only değil; external
--     recipient da bu listede)
--   - Primary key (org_id, channel, recipient_hash) — org-namespaced
--   - bounce_count + soft_window tracking for SOFT_BOUNCE_REPEATED
--     threshold transition (N=3 / 14 day rolling window)
--   - last_source + last_provider audit trail (DSN / PROVIDER_WEBHOOK /
--     MANUAL_API / SMTP_IMMEDIATE)
--   - event_fingerprint dedupe için ayrı bounce_event tablosu (DSN poll
--     veya webhook retry aynı event'i tekrar tekrar saymamalı)

-- 1) Suppression core table
CREATE TABLE notify.email_suppression (
    org_id                       VARCHAR(64)   NOT NULL,
    channel                      VARCHAR(32)   NOT NULL CHECK (channel = 'email'),
    recipient_hash               VARCHAR(128)  NOT NULL,
    recipient_type               VARCHAR(32)   NOT NULL
        CHECK (recipient_type IN ('SUBSCRIBER', 'EXTERNAL')),
    reason                       VARCHAR(32)   NOT NULL
        CHECK (reason IN ('HARD_BOUNCE', 'SOFT_BOUNCE_REPEATED',
                          'SPAM_COMPLAINT', 'MANUAL')),
    first_seen_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    last_seen_at                 TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    bounce_count                 INTEGER       NOT NULL DEFAULT 1,
    soft_window_started_at       TIMESTAMPTZ,
    suppressed_until             TIMESTAMPTZ,
    last_bounce_summary_redacted VARCHAR(256),
    last_source                  VARCHAR(32)
        CHECK (last_source IN ('DSN', 'PROVIDER_WEBHOOK', 'MANUAL_API',
                               'SMTP_IMMEDIATE') OR last_source IS NULL),
    last_provider                VARCHAR(64),
    last_provider_msg_id         VARCHAR(128),
    last_event_fingerprint       VARCHAR(128),
    created_at                   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by                   VARCHAR(128),
    updated_by                   VARCHAR(128),
    PRIMARY KEY (org_id, channel, recipient_hash)
);

CREATE INDEX idx_email_suppression_org_reason
    ON notify.email_suppression (org_id, reason);

CREATE INDEX idx_email_suppression_suppressed_until
    ON notify.email_suppression (suppressed_until)
    WHERE suppressed_until IS NOT NULL;

CREATE INDEX idx_email_suppression_updated_at
    ON notify.email_suppression (updated_at DESC);

-- 2) Per-event dedupe (DSN poll + webhook retry guard)
CREATE TABLE notify.email_bounce_event (
    event_fingerprint  VARCHAR(128)  PRIMARY KEY,
    org_id             VARCHAR(64)   NOT NULL,
    recipient_hash     VARCHAR(128)  NOT NULL,
    provider           VARCHAR(64),
    provider_msg_id    VARCHAR(128),
    source             VARCHAR(32)   NOT NULL
        CHECK (source IN ('DSN', 'PROVIDER_WEBHOOK', 'MANUAL_API',
                          'SMTP_IMMEDIATE')),
    classification     VARCHAR(32)   NOT NULL
        CHECK (classification IN ('HARD_BOUNCE', 'SOFT_BOUNCE',
                                  'SPAM_COMPLAINT', 'MANUAL')),
    received_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    summary_redacted   VARCHAR(256)
);

CREATE INDEX idx_email_bounce_event_org_received
    ON notify.email_bounce_event (org_id, received_at DESC);

CREATE INDEX idx_email_bounce_event_recipient
    ON notify.email_bounce_event (org_id, recipient_hash, received_at DESC);

-- 3) Delivery status enum genişletme: BLOCKED_BY_SUPPRESSION
-- notification_delivery.status existing CHECK constraint'i listeye ekle.
-- V11 trigger terminal listesinde bu yeni status da terminal sayılmalı.

ALTER TABLE notify.notification_delivery
    DROP CONSTRAINT IF EXISTS notification_delivery_status_check;

ALTER TABLE notify.notification_delivery
    ADD CONSTRAINT notification_delivery_status_check
    CHECK (status IN (
        'PENDING', 'ACCEPTED', 'DELIVERED', 'FAILED', 'BOUNCED', 'RETRY',
        'BLOCKED_BY_PREFERENCE', 'BLOCKED_BY_AUTHZ',
        'BLOCKED_BY_IDEMPOTENCY', 'BLOCKED_EXTERNAL_NOT_ALLOWED',
        'BLOCKED_BY_SUPPRESSION'
    ));

-- 4) V11 forward-only state machine trigger: BLOCKED_BY_SUPPRESSION
-- terminal listesine ekle + PENDING'den allowed transition'a ekle.
-- Diğer non-terminal allowed transitions için de yeni status eklendi.

CREATE OR REPLACE FUNCTION notify.notification_delivery_state_audit()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        RETURN NEW;
    END IF;

    -- Terminal state immutability
    IF OLD.status IN ('DELIVERED', 'FAILED', 'BOUNCED',
                      'BLOCKED_BY_PREFERENCE', 'BLOCKED_BY_AUTHZ',
                      'BLOCKED_BY_IDEMPOTENCY', 'BLOCKED_EXTERNAL_NOT_ALLOWED',
                      'BLOCKED_BY_SUPPRESSION')
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
        'BLOCKED_BY_IDEMPOTENCY', 'BLOCKED_EXTERNAL_NOT_ALLOWED',
        'BLOCKED_BY_SUPPRESSION') THEN
        RAISE EXCEPTION 'notification_delivery: PENDING -> % invalid', NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status = 'RETRY' AND NEW.status NOT IN
       ('RETRY', 'ACCEPTED', 'DELIVERED', 'FAILED', 'BOUNCED',
        'BLOCKED_BY_PREFERENCE', 'BLOCKED_BY_AUTHZ',
        'BLOCKED_BY_IDEMPOTENCY', 'BLOCKED_EXTERNAL_NOT_ALLOWED',
        'BLOCKED_BY_SUPPRESSION') THEN
        RAISE EXCEPTION 'notification_delivery: RETRY -> % invalid', NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status = 'ACCEPTED' AND NEW.status NOT IN
       ('ACCEPTED', 'DELIVERED', 'FAILED') THEN
        RAISE EXCEPTION 'notification_delivery: ACCEPTED -> % invalid', NEW.status
            USING ERRCODE = 'check_violation';
    END IF;

    -- Field cleanup on legal transitions (V11 invariants preserved)
    IF OLD.status = 'RETRY' AND NEW.status = 'ACCEPTED' THEN
        NEW.failure_reason = NULL;
        NEW.next_retry_at = NULL;
        NEW.processing_lease_until = NULL;
        NEW.claim_token = NULL;
    END IF;
    IF OLD.status = 'PENDING' AND NEW.status = 'ACCEPTED' THEN
        NEW.failure_reason = NULL;
    END IF;
    IF OLD.status = 'ACCEPTED' AND NEW.status = 'DELIVERED' THEN
        IF NEW.delivered_at IS NULL THEN
            NEW.delivered_at = NOW();
        END IF;
        NEW.failure_reason = NULL;
        NEW.next_retry_at = NULL;
        NEW.processing_lease_until = NULL;
    END IF;
    IF OLD.status = 'ACCEPTED' AND NEW.status = 'FAILED' THEN
        IF NEW.permanent_failure_at IS NULL THEN
            NEW.permanent_failure_at = NOW();
        END IF;
        NEW.next_retry_at = NULL;
        NEW.processing_lease_until = NULL;
    END IF;
    -- New: ACCEPTED → BLOCKED_BY_SUPPRESSION ? hayır; suppression
    -- send öncesi guard'da uygulanır, adapter ACCEPTED almadan delivery
    -- row BLOCKED_BY_SUPPRESSION oluşur. Bu nedenle yalnız PENDING/RETRY
    -- → BLOCKED_BY_SUPPRESSION valid; ACCEPTED → BLOCKED yok.

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON TABLE notify.email_suppression IS
    'Faz 23.8 M7 T4.3.b — email recipient suppression list. Provider IP reputation guard + KVKK-aware (recipient_hash only, raw email never stored).';
COMMENT ON TABLE notify.email_bounce_event IS
    'Faz 23.8 M7 T4.3.b — DSN/webhook event dedupe ledger. Prevents bounce_count double-increment on poll retry or webhook duplicate.';
