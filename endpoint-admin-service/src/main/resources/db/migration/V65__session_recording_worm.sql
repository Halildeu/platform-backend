-- Faz 22.6 C — durable WORM session-recording entry store (ADR-0034 D3: mandatory fail-closed recording,
-- WORM + hash-chain). Each row is one C-1 SessionRecordingChain.Entry persisted by the C-storage
-- DbRecordingSink. Append-only: the app only INSERTs, and a BEFORE UPDATE/DELETE trigger enforces WORM at
-- the DB level (even an operator holding the app role cannot mutate or delete a recorded entry — the trail
-- is immutable). Retention (7y metadata) / legal-hold is an operator + storage concern layered on top.
-- Desired-state only — the remote-access runtime stays disabled-by-default (D10-gated); nothing writes here
-- until a live session records.

CREATE TABLE session_recording_entry (
    chain_id          VARCHAR(128) NOT NULL,
    seq               BIGINT       NOT NULL,
    timestamp_millis  BIGINT       NOT NULL,
    kind              VARCHAR(32)  NOT NULL,
    content_hash      VARCHAR(256) NOT NULL,
    previous_hash     CHAR(64)     NOT NULL,
    entry_hash        CHAR(64)     NOT NULL,
    recorded_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (chain_id, seq),
    CONSTRAINT uq_session_recording_entry_hash UNIQUE (entry_hash),
    CONSTRAINT chk_session_recording_seq_nonneg CHECK (seq >= 0),
    -- the chain link + entry hashes are always 64-char lowercase-hex SHA-256 (CertThumbprint.ofDer);
    -- content_hash is caller-supplied (a content digest in prod) and deliberately NOT format-constrained.
    CONSTRAINT chk_session_recording_link_hex
        CHECK (previous_hash ~ '^[0-9a-f]{64}$' AND entry_hash ~ '^[0-9a-f]{64}$')
);

-- WORM enforcement: block any UPDATE/DELETE on a recorded entry (append-only is a security invariant, not a
-- convention). An attempt raises an error so a tamper attempt fails loudly rather than silently mutating.
CREATE OR REPLACE FUNCTION session_recording_no_mutate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'session_recording_entry is append-only (WORM): % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_session_recording_worm
    BEFORE UPDATE OR DELETE ON session_recording_entry
    FOR EACH ROW EXECUTE FUNCTION session_recording_no_mutate();

COMMENT ON TABLE session_recording_entry IS
    'Faz 22.6 C: durable WORM recording hash-chain (ADR-0033 §6, ADR-0034 D3). Append-only — UPDATE/DELETE '
    'blocked by trg_session_recording_worm. Each row = one tamper-evident SessionRecordingChain entry.';
