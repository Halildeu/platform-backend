-- V1 — meeting-service baseline schema (Faz 24 meeting intelligence, #410)
--
-- Per-service Flyway sequence: meeting-service starts at V1 (endpoint-admin's
-- V57 is a SEPARATE, independent history table — they never share a sequence).
--
-- Creates the 4 core meeting-intelligence tables:
--   1. meetings            — root aggregate (one row per scheduled/held meeting)
--   2. meeting_sessions    — recorded/live sessions belonging to a meeting (1:N)
--   3. meeting_actions     — action items extracted from a meeting (1:N)
--   4. meeting_decisions   — decisions recorded for a meeting (1:N)
--
-- Multi-tenancy: every table carries `tenant_id UUID NOT NULL` (legacy scope
-- key) + `org_id UUID` (nullable canonical scope key) — the SAME org_id compat
-- pattern endpoint-admin V29 introduced. A BEFORE INSERT/UPDATE trigger
-- back-fills org_id from tenant_id when a legacy writer leaves it null. org_id
-- stays NULLABLE until a later cleanup migration (after a deploy/rollback
-- window with mismatch=0 evidence) — matching the endpoint-admin charter.
-- Each table also carries a `<tbl>_org_id_match CHECK (org_id IS NULL OR
-- org_id = tenant_id)` invariant (endpoint-admin V30) so an explicit
-- tenant_id=A / org_id=B drift is rejected at the DB level — the trigger only
-- back-fills a NULL org_id, it never corrects a mismatch.
--
-- Optimistic locking: every table has a `version BIGINT NOT NULL DEFAULT 0`
-- column bound to a JPA @Version field.
--
-- Sub-resource FKs (session/action/decision -> meeting) are COMPOSITE
-- `(meeting_id, tenant_id) REFERENCES meetings(id, tenant_id)` (targeting the
-- `uq_meetings_id_tenant` UNIQUE key) so a child can never bind to a meeting in
-- a different tenant (cross-tenant child drift guard), and they cascade on
-- delete so a meeting teardown removes its children atomically. The child
-- tables carry their own tenant_id/org_id so tenant-scoped queries never have
-- to join the parent (consistent with endpoint-admin's per-table tenant
-- columns).

-- ============================================================
-- Trigger function: BEFORE INSERT/UPDATE — fill org_id from tenant_id
-- when caller leaves it null. Idempotent + side-effect-free for callers
-- that already supply org_id. Mirrors endpoint-admin V29
-- endpoint_org_id_compat_fill().
-- ============================================================

CREATE OR REPLACE FUNCTION meeting_org_id_compat_fill()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.org_id IS NULL AND NEW.tenant_id IS NOT NULL THEN
        NEW.org_id := NEW.tenant_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION meeting_org_id_compat_fill() IS
    'Faz 24 meeting-service org_id compat layer (V1). Fills org_id from tenant_id when caller leaves it null. Safe to remove after a cleanup migration drops tenant_id.';

-- ============================================================
-- Table 1: meetings (root aggregate)
-- ============================================================

CREATE TABLE meetings (
    id              UUID         NOT NULL,
    tenant_id       UUID         NOT NULL,
    org_id          UUID,
    title           VARCHAR(512) NOT NULL,
    description     VARCHAR(4000),
    status          VARCHAR(32)  NOT NULL DEFAULT 'SCHEDULED',
    scheduled_start TIMESTAMPTZ,
    scheduled_end   TIMESTAMPTZ,
    organizer_subject VARCHAR(255) NOT NULL,
    created_by_subject VARCHAR(255) NOT NULL,
    last_updated_by_subject VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_meetings PRIMARY KEY (id),
    -- org_id/tenant_id invariant (endpoint-admin V30): org_id is either NULL
    -- (legacy row, pre-trigger) or exactly equal to tenant_id. Blocks a
    -- caller writing tenant_id=A / org_id=B drift at the DB level — the
    -- trigger only back-fills NULL, it does not correct a mismatch.
    CONSTRAINT meetings_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id),
    -- Composite key target so child tables can FK on (meeting_id, tenant_id),
    -- guaranteeing a child can never bind to another tenant's meeting.
    CONSTRAINT uq_meetings_id_tenant UNIQUE (id, tenant_id)
);

CREATE INDEX idx_meetings_org_id ON meetings(org_id);
CREATE INDEX idx_meetings_tenant_status ON meetings(tenant_id, status);
CREATE INDEX idx_meetings_org_updated ON meetings(org_id, updated_at DESC);

DROP TRIGGER IF EXISTS meetings_org_id_compat ON meetings;
CREATE TRIGGER meetings_org_id_compat
    BEFORE INSERT OR UPDATE ON meetings
    FOR EACH ROW EXECUTE FUNCTION meeting_org_id_compat_fill();

-- ============================================================
-- Table 2: meeting_sessions (1:N -> meetings)
-- ============================================================

CREATE TABLE meeting_sessions (
    id              UUID         NOT NULL,
    meeting_id      UUID         NOT NULL,
    tenant_id       UUID         NOT NULL,
    org_id          UUID,
    session_label   VARCHAR(256),
    started_at      TIMESTAMPTZ,
    ended_at        TIMESTAMPTZ,
    recording_uri   VARCHAR(2048),
    transcript_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_by_subject VARCHAR(255) NOT NULL,
    last_updated_by_subject VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_meeting_sessions PRIMARY KEY (id),
    CONSTRAINT meeting_sessions_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id),
    -- Composite tenant-FK: a session can only reference a meeting that shares
    -- its tenant_id (cross-tenant child drift guard). ON DELETE CASCADE keeps
    -- the meeting-teardown semantics.
    CONSTRAINT fk_meeting_sessions_meeting
        FOREIGN KEY (meeting_id, tenant_id) REFERENCES meetings(id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX idx_meeting_sessions_meeting_id ON meeting_sessions(meeting_id);
CREATE INDEX idx_meeting_sessions_org_id ON meeting_sessions(org_id);

DROP TRIGGER IF EXISTS meeting_sessions_org_id_compat ON meeting_sessions;
CREATE TRIGGER meeting_sessions_org_id_compat
    BEFORE INSERT OR UPDATE ON meeting_sessions
    FOR EACH ROW EXECUTE FUNCTION meeting_org_id_compat_fill();

-- ============================================================
-- Table 3: meeting_actions (1:N -> meetings)
-- ============================================================

CREATE TABLE meeting_actions (
    id              UUID         NOT NULL,
    meeting_id      UUID         NOT NULL,
    tenant_id       UUID         NOT NULL,
    org_id          UUID,
    description     VARCHAR(2000) NOT NULL,
    assignee_subject VARCHAR(255),
    status          VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    due_at          TIMESTAMPTZ,
    created_by_subject VARCHAR(255) NOT NULL,
    last_updated_by_subject VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_meeting_actions PRIMARY KEY (id),
    CONSTRAINT meeting_actions_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id),
    -- Composite tenant-FK: an action can only reference a meeting that shares
    -- its tenant_id (cross-tenant child drift guard). ON DELETE CASCADE keeps
    -- the meeting-teardown semantics.
    CONSTRAINT fk_meeting_actions_meeting
        FOREIGN KEY (meeting_id, tenant_id) REFERENCES meetings(id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX idx_meeting_actions_meeting_id ON meeting_actions(meeting_id);
CREATE INDEX idx_meeting_actions_org_id ON meeting_actions(org_id);

DROP TRIGGER IF EXISTS meeting_actions_org_id_compat ON meeting_actions;
CREATE TRIGGER meeting_actions_org_id_compat
    BEFORE INSERT OR UPDATE ON meeting_actions
    FOR EACH ROW EXECUTE FUNCTION meeting_org_id_compat_fill();

-- ============================================================
-- Table 4: meeting_decisions (1:N -> meetings)
-- ============================================================

CREATE TABLE meeting_decisions (
    id              UUID         NOT NULL,
    meeting_id      UUID         NOT NULL,
    tenant_id       UUID         NOT NULL,
    org_id          UUID,
    title           VARCHAR(512) NOT NULL,
    detail          VARCHAR(4000),
    decided_by_subject VARCHAR(255),
    decided_at      TIMESTAMPTZ,
    created_by_subject VARCHAR(255) NOT NULL,
    last_updated_by_subject VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_meeting_decisions PRIMARY KEY (id),
    CONSTRAINT meeting_decisions_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id),
    -- Composite tenant-FK: a decision can only reference a meeting that shares
    -- its tenant_id (cross-tenant child drift guard). ON DELETE CASCADE keeps
    -- the meeting-teardown semantics.
    CONSTRAINT fk_meeting_decisions_meeting
        FOREIGN KEY (meeting_id, tenant_id) REFERENCES meetings(id, tenant_id) ON DELETE CASCADE
);

CREATE INDEX idx_meeting_decisions_meeting_id ON meeting_decisions(meeting_id);
CREATE INDEX idx_meeting_decisions_org_id ON meeting_decisions(org_id);

DROP TRIGGER IF EXISTS meeting_decisions_org_id_compat ON meeting_decisions;
CREATE TRIGGER meeting_decisions_org_id_compat
    BEFORE INSERT OR UPDATE ON meeting_decisions
    FOR EACH ROW EXECUTE FUNCTION meeting_org_id_compat_fill();
