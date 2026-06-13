-- Faz 22.6 D10 post-pilot hardening #2 — durable WORM approval-DECISION audit (Codex 019ec29a). Every
-- dual-control approval outcome (RECORDED + each denial reason) is persisted append-only, so "who approved /
-- denied what, when" survives restart and cannot be mutated by the app role. This is the APPROVAL-decision
-- audit, distinct from the broker's OPERATION recording (V65 session_recording_entry). The recorded decision
-- reason is audit-INTERNAL truth (never an external oracle — the REST transport collapses denials).
--
-- Append-only/WORM via a BEFORE UPDATE/DELETE/TRUNCATE trigger (the V65 pattern, own function/trigger names) —
-- even an operator holding the app role cannot rewrite the approval trail. This is durable WORM, NOT a
-- cryptographically-anchored hash-chain (that would be a separate, heavier ledger slice). Desired-state only —
-- the remote-access runtime stays disabled-by-default (D10-gated); nothing writes here until a live approval.

CREATE TABLE remote_bridge_approval_decision_audit (
    id                         BIGSERIAL    PRIMARY KEY,
    session_id                 VARCHAR(256) NOT NULL,
    operator_tenant_id         VARCHAR(256) NOT NULL,
    operator_subject           VARCHAR(512) NOT NULL,
    session_start_epoch_millis BIGINT       NOT NULL,
    -- the approver identity is absent on some pre-flow denials (e.g. a tenant mismatch) — nullable
    approver_principal         VARCHAR(512),
    approver_tenant_id         VARCHAR(256),
    decision                   VARCHAR(64)  NOT NULL,
    requested_capabilities     TEXT         NOT NULL,
    -- the approver's approved set is present only on a RECORDED decision — nullable
    approved_capabilities      TEXT,
    event_epoch_millis         BIGINT       NOT NULL,
    recorded_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_rb_approval_audit_decision_nonblank
        CHECK (length(btrim(decision)) > 0),
    CONSTRAINT chk_rb_approval_audit_requested_caps_nonblank
        CHECK (length(btrim(requested_capabilities)) > 0),
    -- defense-in-depth (Codex 019ec29a): a RECORDED decision MUST carry the approved capability set; a denial
    -- carries none. The recorder already enforces this — the DB CHECK closes a direct-misuse fail-open.
    CONSTRAINT chk_rb_approval_audit_recorded_has_approved
        CHECK (decision <> 'RECORDED'
            OR (approved_capabilities IS NOT NULL AND length(btrim(approved_capabilities)) > 0))
);

CREATE INDEX idx_rb_approval_audit_session
    ON remote_bridge_approval_decision_audit
    (session_id, operator_tenant_id, operator_subject, session_start_epoch_millis);

CREATE INDEX idx_rb_approval_audit_recorded_at
    ON remote_bridge_approval_decision_audit (recorded_at);

CREATE INDEX idx_rb_approval_audit_approver
    ON remote_bridge_approval_decision_audit (approver_principal, recorded_at);

-- WORM enforcement: block any UPDATE/DELETE/TRUNCATE on a recorded approval-decision row (append-only is a
-- security invariant — the approval trail is immutable even to the app role).
CREATE OR REPLACE FUNCTION remote_bridge_approval_decision_audit_no_mutate()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'remote_bridge_approval_decision_audit is append-only (WORM): % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_rb_approval_audit_worm
    BEFORE UPDATE OR DELETE ON remote_bridge_approval_decision_audit
    FOR EACH ROW EXECUTE FUNCTION remote_bridge_approval_decision_audit_no_mutate();

CREATE TRIGGER trg_rb_approval_audit_no_truncate
    BEFORE TRUNCATE ON remote_bridge_approval_decision_audit
    FOR EACH STATEMENT EXECUTE FUNCTION remote_bridge_approval_decision_audit_no_mutate();
