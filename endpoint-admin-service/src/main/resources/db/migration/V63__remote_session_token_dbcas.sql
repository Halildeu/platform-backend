-- Faz 22.6 B2.2b — DB-CAS backing for the TokenLifecycleStore (single-use + revocation + TTL).
-- Codex 019eb54b: closed state machine (UNSEEN = no row); atomic single-use via INSERT ... ON CONFLICT;
-- revoke/consume ordering recorded (revoked_at / consumed_at); time decision DB-authoritative.
-- This is desired-state schema only — the runtime that uses it stays disabled-by-default (D10-gated).

CREATE TABLE remote_session_token (
    jti          VARCHAR(255) PRIMARY KEY,
    -- UNSEEN is represented by the absence of a row; INVALID is reserved for malformed entries.
    state        VARCHAR(16)  NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    consumed_at  TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_remote_session_token_state CHECK (state IN ('USED', 'REVOKED', 'EXPIRED', 'INVALID'))
);

-- TTL cleanup support (cleanup keeps REVOKED/EXPIRED rows for audit; archive/retention is separate).
CREATE INDEX ix_remote_session_token_expires_at ON remote_session_token (expires_at);

COMMENT ON TABLE remote_session_token IS
    'Faz 22.6 remote-access session token lifecycle (single source of truth). UNSEEN=no row. '
    'Atomic single-use via INSERT ON CONFLICT; revoke authoritative (always wins).';
