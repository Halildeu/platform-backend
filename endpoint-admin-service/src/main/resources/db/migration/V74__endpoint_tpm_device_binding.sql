-- Faz 22.6 #548 slice-1 step-4 (prerequisite) — TPM enrollment binding persistence.
-- Codex 019efada decision A: the canonical device-key SESSION verifier
-- (DEVICE_KEY_ATTESTATION_REAL, a later slice) must bind AK<->EK. It cannot rest
-- on `device_key_pub == mTLS leaf` + `Certify` + `EK-root` alone — a software AK,
-- or a genuine EK cert borrowed from another TPM, would otherwise pass. Enrollment
-- already PROVES AK<->EK at V10 (TPM2_MakeCredential/ActivateCredential) but, until
-- now, persisted nothing queryable (only the Vault-issued mTLS cert). This table is
-- that persisted, V10-proven enrollment TPM record: the session verifier matches a
-- live response's akName / EK / device-key against the active row for the device.
--
-- Trustable rows only: device_id is NOT NULL (a null-device enrollment yields NO row,
-- not a non-trustable one — see TpmEnrollmentCompletionService). akName is stored RAW
-- (the TPM Name is the canonical compare input); AK pub / EK cert / device-key SPKI are
-- SHA-256 digests (enough for the verifier match; no raw DER kept).
--
-- Lifecycle (Codex): single ACTIVE binding per (tenant_id, device_id) via a partial
-- unique index; re-enrollment soft-revokes the prior active row (revoked_at +
-- revoked_reason) and inserts a new active one, atomically. Revoking frees the partial
-- unique slot. Cert revoke/decommission is NOT coupled here — the session verifier's
-- active-machine-cert gate (ConnectedDeviceResolver) is the primary revocation; this
-- table's revoked_at is for re-enrollment supersede + a future explicit binding revoke.

CREATE TABLE endpoint_tpm_device_binding (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    device_id UUID NOT NULL REFERENCES endpoint_devices (id) ON DELETE CASCADE,
    endpoint_enrollment_id UUID NOT NULL REFERENCES endpoint_enrollments (id) ON DELETE CASCADE,
    ak_name BYTEA NOT NULL,
    ak_pub_sha256 VARCHAR(64) NOT NULL,
    ek_cert_sha256 VARCHAR(64) NOT NULL,
    device_key_spki_sha256 VARCHAR(64) NOT NULL,
    enrolled_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoked_reason VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    -- one binding row per enrollment (an enrollment cannot mint two bindings)
    CONSTRAINT uq_tpm_binding_enrollment UNIQUE (endpoint_enrollment_id),
    -- revoked_at and revoked_reason are set together or not at all
    CONSTRAINT ck_tpm_binding_revocation_pair CHECK (
        (revoked_at IS NULL AND revoked_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_reason IS NOT NULL)
    )
);

-- At most ONE active (un-revoked) binding per (tenant_id, device_id). A re-enrollment
-- must soft-revoke the prior active row before inserting; a race that tries to leave two
-- active rows hits this index and fails closed (DataIntegrityViolationException), never a
-- silent overwrite.
CREATE UNIQUE INDEX uq_tpm_binding_active_device
    ON endpoint_tpm_device_binding (tenant_id, device_id)
    WHERE revoked_at IS NULL;

-- The verifier looks up the active binding by (tenant_id, device_id); index the lookup.
CREATE INDEX idx_tpm_binding_tenant_device
    ON endpoint_tpm_device_binding (tenant_id, device_id);
