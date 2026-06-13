-- Faz 22.6 D10 post-pilot hardening — DB-backed durable approval-grant store (Codex 019ec29a). Persists the
-- recorded dual-control session grant — a SHORT-LIVED operational authority: the capabilities granted to a
-- verified operator for ONE session incarnation — so it survives restart / multi-replica, replacing the
-- process-local InMemoryApprovalGrantStore (which is forbidden in a production-like profile). Written by
-- RemoteSessionApprovalRecorder ONLY after a dual-control approval ALLOWs; read by ApprovalBackedOwnerTokenGate
-- at PERMIT time. Fail-closed: a missing OR expired row grants nothing.
--
-- The key is the FULL session incarnation (sessionId + operator tenant + RAW operator subject + sessionStart) —
-- the same key the in-memory store uses — so a reused sessionId in a later incarnation can never inherit an
-- earlier grant. capabilities is a deterministic comma-separated list of RemoteSessionCapability enum names
-- (the store parses it ALL-OR-NOTHING: an unknown token grants nothing). Desired-state only — the remote-access
-- runtime stays disabled-by-default (D10-gated); nothing writes here until a live approval is recorded.

CREATE TABLE remote_bridge_approval_grant (
    session_id                 VARCHAR(256) NOT NULL,
    operator_tenant_id         VARCHAR(256) NOT NULL,
    operator_subject           VARCHAR(256) NOT NULL,
    session_start_epoch_millis BIGINT       NOT NULL,
    capabilities               TEXT         NOT NULL,
    expires_at_epoch_millis    BIGINT       NOT NULL,
    recorded_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- the grant is pinned to the full session incarnation; a re-recorded approval upserts this same row
    PRIMARY KEY (session_id, operator_tenant_id, operator_subject, session_start_epoch_millis),
    -- a blank capability list would be a fail-open hole (the store also refuses an empty grant at write time)
    CONSTRAINT chk_rb_approval_grant_caps_nonblank CHECK (length(btrim(capabilities)) > 0)
);

-- supports an expiry-sweep / housekeeping query (an expired grant already fail-closes on read, but stale rows
-- can be pruned by an operator job)
CREATE INDEX idx_rb_approval_grant_expiry ON remote_bridge_approval_grant (expires_at_epoch_millis);
