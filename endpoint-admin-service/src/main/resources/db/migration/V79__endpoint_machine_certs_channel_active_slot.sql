-- Faz 22.6 #1580/#548 bridge fixup: AD_CS product-channel enrollment and
-- VAULT_TPM hardware-attestation enrollment are independent trust channels.
--
-- V75 intentionally introduced channel semantics, but kept a global
-- one-active-cert-per-device slot. Live evidence from the attended VIEW_ONLY
-- smoke showed the side effect: a pre-bound TPM completion revoked the device's
-- AD_CS credential, so lifecycle/remote-bridge product traffic failed closed
-- even though the TPM marker remained valid.
--
-- Keep the important invariant, but scope it correctly: one active cert per
-- device/channel. This allows an endpoint to hold:
--   * one active AD_CS cert for product lifecycle/remote-bridge, and
--   * one active VAULT_TPM cert for TPM/device-key attestation.
-- Same-channel rotation still requires revoke-before-insert.

DROP INDEX IF EXISTS uq_endpoint_machine_certs_device_active;

CREATE UNIQUE INDEX uq_endpoint_machine_certs_device_channel_active
    ON endpoint_machine_certs (device_id, channel)
    WHERE revoked_at IS NULL;

COMMENT ON INDEX uq_endpoint_machine_certs_device_channel_active IS
    'One active machine cert per endpoint device and enrollment channel; AD_CS and VAULT_TPM coexist.';
