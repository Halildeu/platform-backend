-- Faz 22.6 #548 strong-path runtime grant.
--
-- The device-key broker runs with a least-privilege DB role
-- (`endpoint_admin_remote_bridge`) and the canonical
-- DEVICE_KEY_ATTESTATION_REAL verifier must read the persisted TPM enrollment
-- binding before it can prove:
--   attested device key == live mTLS leaf == persisted V10 TPM binding.
--
-- Keep this conditional because local/dev databases may not create the
-- dedicated remote-bridge role. The table owner/migration role remains
-- unaffected; production-like deploys that create the role get the minimum
-- SELECT permission and no write authority.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'endpoint_admin_remote_bridge') THEN
        GRANT SELECT ON TABLE endpoint_tpm_device_binding TO endpoint_admin_remote_bridge;
    END IF;
END
$$;
