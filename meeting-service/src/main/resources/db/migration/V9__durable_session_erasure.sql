-- Durable, privacy-safe meeting-session erasure coordinator and permanent tombstone.
-- Raw source ids are retained only while remote erasure is incomplete; COMPLETE
-- rows keep the one-way hash needed to reject replays without retaining the alias.
CREATE TABLE meeting_session_erasure (
    session_id              UUID PRIMARY KEY,
    tenant_id               UUID         NOT NULL,
    org_id                  UUID,
    meeting_id              UUID         NOT NULL,
    source_session_id       VARCHAR(128),
    source_session_hash     VARCHAR(64),
    status                  VARCHAR(16)  NOT NULL,
    local_erased            BOOLEAN      NOT NULL DEFAULT FALSE,
    remote_erased           BOOLEAN      NOT NULL DEFAULT FALSE,
    claim_token             UUID,
    processing_owner        VARCHAR(128),
    claimed_at              TIMESTAMPTZ,
    lease_expires_at        TIMESTAMPTZ,
    next_attempt_at         TIMESTAMPTZ  NOT NULL,
    attempts                INTEGER      NOT NULL DEFAULT 0,
    last_error_code         VARCHAR(64),
    requested_at            TIMESTAMPTZ  NOT NULL,
    completed_at            TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ  NOT NULL,
    version                 BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT meeting_session_erasure_org_match
        CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT meeting_session_erasure_source_hash
        CHECK (source_session_hash IS NULL OR source_session_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT meeting_session_erasure_status
        CHECK (status IN ('PENDING', 'ACTIVE', 'HELD', 'COMPLETE')),
    CONSTRAINT meeting_session_erasure_attempts CHECK (attempts >= 0),
    CONSTRAINT meeting_session_erasure_completion
        CHECK ((status = 'COMPLETE' AND local_erased AND remote_erased
                    AND completed_at IS NOT NULL AND source_session_id IS NULL)
            OR (status <> 'COMPLETE' AND completed_at IS NULL)),
    CONSTRAINT meeting_session_erasure_active_lease
        CHECK ((status = 'ACTIVE' AND claim_token IS NOT NULL AND lease_expires_at IS NOT NULL)
            OR (status <> 'ACTIVE' AND claim_token IS NULL AND lease_expires_at IS NULL))
);

CREATE INDEX idx_meeting_session_erasure_due
    ON meeting_session_erasure(status, next_attempt_at, requested_at)
    WHERE status IN ('PENDING', 'HELD');

CREATE INDEX idx_meeting_session_erasure_active_lease
    ON meeting_session_erasure(lease_expires_at)
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX ux_meeting_session_erasure_source_hash
    ON meeting_session_erasure(tenant_id, meeting_id, source_session_hash)
    WHERE source_session_hash IS NOT NULL;

CREATE TABLE meeting_session_erasure_audit (
    id                          UUID PRIMARY KEY,
    session_id                  UUID         NOT NULL,
    state                       VARCHAR(16)  NOT NULL,
    local_deleted_count         INTEGER      NOT NULL DEFAULT 0,
    remote_deleted_count        INTEGER      NOT NULL DEFAULT 0,
    audit_payload               VARCHAR(32)  NOT NULL DEFAULT 'metadata-only',
    executed_at                 TIMESTAMPTZ  NOT NULL,
    CONSTRAINT meeting_session_erasure_audit_state
        CHECK (state IN ('PENDING', 'ACTIVE', 'HELD', 'COMPLETE')),
    CONSTRAINT meeting_session_erasure_audit_counts
        CHECK (local_deleted_count >= 0 AND remote_deleted_count >= 0),
    CONSTRAINT meeting_session_erasure_audit_payload
        CHECK (audit_payload = 'metadata-only')
);

CREATE INDEX idx_meeting_session_erasure_audit_session
    ON meeting_session_erasure_audit(session_id, executed_at DESC);

COMMENT ON TABLE meeting_session_erasure IS
    'Permanent session-erasure tombstone and lease-fenced coordinator. Contains identifiers and bounded state only; never transcript, token, user text or exception messages.';
COMMENT ON COLUMN meeting_session_erasure.source_session_hash IS
    'SHA-256 of the external/source session alias used only as a replay fence.';
COMMENT ON TABLE meeting_session_erasure_audit IS
    'Append-only metadata-only erasure transition evidence; no personal-data payload.';
