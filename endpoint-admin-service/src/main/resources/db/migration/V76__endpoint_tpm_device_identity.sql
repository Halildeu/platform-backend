-- Faz 22.6 #548 Phase 1.5 — canonical TPM device-identity map (Codex 019eff93
-- AGREE, P0-4).
--
-- The SOLE adoption authority for the TPM-native channel: maps a tenant-scoped TPM
-- Endorsement-Key public-key digest (ek_pub_sha256, server-derived from the V2-validated
-- EK at /nonce — never caller input) to its canonical endpoint_devices row.
--
-- Why a dedicated table (NOT endpoint_devices.machine_fingerprint): the device
-- machine_fingerprint is AGENT-supplied on the AD_CS channel. Adopting a TPM device by
-- a "tpm:{ek}" pseudo-fingerprint would let a malicious/buggy AD_CS enrollment in the
-- same tenant pre-write that fingerprint and hijack the binding to the wrong device.
-- This table is server-derived, tenant-scoped, and immune to that path; the AD_CS
-- service additionally rejects any agent hostname/fingerprint carrying a tpm-/tpm:
-- prefix (defense-in-depth). Identity = (tenant_id, ek_pub_sha256); EK is the stable
-- hardware root (the AK is a rotatable attestation key and is intentionally NOT part of
-- the identity), matching Teleport Device Trust / Intune device-registration models.
--
-- Insert-once: a device decommission does NOT delete the identity row (re-enrollment of
-- the same EK then resolves the decommissioned device and is denied — no-revive). The
-- row is removed only by ON DELETE CASCADE when the device row itself is deleted.

CREATE TABLE endpoint_tpm_device_identity (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    ek_pub_sha256 VARCHAR(64) NOT NULL,
    device_id UUID NOT NULL REFERENCES endpoint_devices (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    -- One canonical device per (tenant, EK). A concurrent double-completion of the same
    -- EK fails closed here (DataIntegrityViolationException), never a silent second device.
    CONSTRAINT uq_tpm_device_identity_tenant_ek UNIQUE (tenant_id, ek_pub_sha256)
);

CREATE INDEX idx_tpm_device_identity_device
    ON endpoint_tpm_device_identity (device_id);
