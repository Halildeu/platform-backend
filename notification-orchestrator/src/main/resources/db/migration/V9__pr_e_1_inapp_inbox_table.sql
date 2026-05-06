-- Faz 23.3 PR-E.1 — In-app inbox backend table (charter scope).
--
-- notification_inbox: subscriber-addressed in-app inbox rows. Independent
-- state machine (UNREAD/READ/ARCHIVED) over notification_delivery (which
-- tracks per-channel send attempts). Inbox lifecycle orthogonal to delivery
-- status — a delivery may be DELIVERED while inbox stays UNREAD until the
-- subscriber opens the message.
--
-- Design notes:
--   * Independent table (Option A vs reusing notification_delivery channel='in-app'
--     with extra columns) — cleaner separation, dedicated index footprint
--     for inbox listing + unread badge queries.
--   * Inbox row created at intent fan-out / dispatch time when 'in-app'
--     channel selected (next sub-PR PR-E.2 wires InAppInboxAdapter).
--   * Idempotent insert via UNIQUE (org_id, intent_id, subscriber_id) — a
--     given intent yields at most one inbox row per subscriber, even if
--     dispatch retries.
--
-- Out of scope (this PR):
--   * InAppInboxAdapter integration (PR-E.2)
--   * WebSocket / SSE real-time badge endpoint (PR-E.2 / 23.4)
--   * Bulk archive (mass mark-read) endpoint (deferred — current scope is
--     single-row mutations)
--   * TTL-based auto-archive worker (deferred to retention policy review)

CREATE TABLE notify.notification_inbox (
    id BIGSERIAL PRIMARY KEY,
    intent_id VARCHAR(64) NOT NULL,
    org_id VARCHAR(64) NOT NULL,
    subscriber_id VARCHAR(128) NOT NULL,

    -- Rendered content (snapshot at fan-out time; template version pinned via
    -- notification_intent.template_version, no runtime re-render)
    subject VARCHAR(500),
    body_text TEXT,
    body_html TEXT,
    locale VARCHAR(16) NOT NULL DEFAULT 'tr-TR',

    -- Metadata for client filtering/sorting
    topic_key VARCHAR(128) NOT NULL,
    severity VARCHAR(16) NOT NULL,

    -- Inbox state machine: UNREAD → READ → ARCHIVED (terminal); ARCHIVED is
    -- soft-delete (rows kept for audit; KVKK erasure handles permanent
    -- deletion via existing erasure flow).
    state VARCHAR(16) NOT NULL DEFAULT 'UNREAD'
        CHECK (state IN ('UNREAD', 'READ', 'ARCHIVED')),
    read_at TIMESTAMPTZ,
    archived_at TIMESTAMPTZ,

    -- Lifecycle
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ
);

-- Idempotent insert: one inbox row per (org, intent, subscriber)
CREATE UNIQUE INDEX uq_inbox_org_intent_subscriber
    ON notify.notification_inbox (org_id, intent_id, subscriber_id);

-- Inbox listing: subscriber's non-archived inbox, newest first
-- (90% of GET /inbox/me queries hit this path)
CREATE INDEX idx_inbox_subscriber_active
    ON notify.notification_inbox (org_id, subscriber_id, created_at DESC)
    WHERE state != 'ARCHIVED';

-- Unread badge count: WHERE state='UNREAD' lookup
-- (covers GET /inbox/me/unread-count + WS subscription)
CREATE INDEX idx_inbox_unread_badge
    ON notify.notification_inbox (org_id, subscriber_id)
    WHERE state = 'UNREAD';

-- Intent fan-out join: list all inbox rows for an intent (admin/audit)
CREATE INDEX idx_inbox_intent
    ON notify.notification_inbox (org_id, intent_id);

-- State consistency triggers: auto-set read_at/archived_at on state transition
CREATE OR REPLACE FUNCTION notify.notification_inbox_state_audit()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.state = 'READ' AND (OLD.state IS NULL OR OLD.state != 'READ') THEN
        NEW.read_at = COALESCE(NEW.read_at, NOW());
    END IF;
    IF NEW.state = 'ARCHIVED' AND (OLD.state IS NULL OR OLD.state != 'ARCHIVED') THEN
        NEW.archived_at = COALESCE(NEW.archived_at, NOW());
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_notification_inbox_state_audit
BEFORE INSERT OR UPDATE ON notify.notification_inbox
FOR EACH ROW EXECUTE FUNCTION notify.notification_inbox_state_audit();

COMMENT ON TABLE notify.notification_inbox IS
    'Faz 23.3 PR-E.1: subscriber-addressed in-app inbox; independent state '
    'machine over delivery (UNREAD/READ/ARCHIVED).';
COMMENT ON COLUMN notify.notification_inbox.state IS
    'UNREAD (initial) → READ (subscriber opens) → ARCHIVED (soft-delete). '
    'Terminal: ARCHIVED. read_at/archived_at auto-set via trigger.';
COMMENT ON COLUMN notify.notification_inbox.expires_at IS
    'Optional TTL for auto-archive worker (deferred to retention policy review).';
