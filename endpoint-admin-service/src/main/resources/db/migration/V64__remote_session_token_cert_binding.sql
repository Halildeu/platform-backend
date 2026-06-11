-- Faz 22.6 B1.1 — cert-bound session token (RFC 8705 mTLS proof-of-possession; Codex 019eb54b B1 plan).
-- Additive: pin the bound certificate thumbprint (SHA-256 hex, 64 chars) at consume so single-use AND
-- cert-binding are one atomic record (B1.1b). NULLABLE on purpose — a legacy-unbound token (issued
-- before binding, or under the legacy-allow feature flag) has no thumbprint; whether such a token may go
-- ACTIVE is a fail-closed feature-flag decision in the runtime (B1.1c), not a schema concern.
-- Desired-state only — the runtime stays disabled-by-default (D10-gated).

ALTER TABLE remote_session_token
    ADD COLUMN bound_cert_thumbprint VARCHAR(64);

-- Format guarantee (Codex 019eb54b B1.1a REVISE #2): when present, the thumbprint MUST be a 64-char
-- lowercase-hex SHA-256 — a bad/garbled payload is rejected at the DB, aiding future attestation/PKI.
ALTER TABLE remote_session_token
    ADD CONSTRAINT chk_remote_session_token_thumbprint
        CHECK (bound_cert_thumbprint IS NULL OR bound_cert_thumbprint ~ '^[0-9a-f]{64}$');

COMMENT ON COLUMN remote_session_token.bound_cert_thumbprint IS
    'Faz 22.6 B1.1: SHA-256 hex thumbprint of the client cert this token is bound to (RFC 8705). '
    'NULL = legacy-unbound (feature-flag gated). Pinned atomically at consume; enforced every heartbeat.';
