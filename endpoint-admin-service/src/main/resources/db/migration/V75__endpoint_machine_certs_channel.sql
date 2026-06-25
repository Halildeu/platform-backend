-- Faz 22.6 #548 Phase 1.5 — channel-aware endpoint_machine_certs (Codex 019eff93
-- AGREE, P0-3 + P1-5 + Inv-1/Inv-2).
--
-- Two enrollment channels now write machine-cert rows:
--   * AD_CS     — the existing ADR-0029 self-enrollment (SAN adcomputer:{objectGUID});
--                 object_guid is the AD identity and stays NOT NULL for this channel.
--   * VAULT_TPM — the TPM-native device-completion (Faz 22.6 #548, SAN tpm:{ek_pub_sha256});
--                 there is NO AD objectGUID, so object_guid is NULL for this channel.
--
-- `channel` carries a permanent DEFAULT 'AD_CS' (P1-5): a rolling deploy where an old
-- pod still INSERTs without the column keeps working, and the CHECK below fail-closes
-- any TPM row that wrongly defaults to AD_CS (a VAULT_TPM row MUST set channel
-- explicitly because its object_guid is NULL). The default is intentionally kept, not
-- dropped in a later migration.

ALTER TABLE endpoint_machine_certs
    ADD COLUMN channel VARCHAR(16) NOT NULL DEFAULT 'AD_CS';

-- TPM channel has no AD objectGUID — relax the column and pin nullability per channel.
ALTER TABLE endpoint_machine_certs
    ALTER COLUMN object_guid DROP NOT NULL;

ALTER TABLE endpoint_machine_certs
    ADD CONSTRAINT ck_endpoint_machine_certs_channel_object_guid CHECK (
        (channel = 'AD_CS' AND object_guid IS NOT NULL)
        OR (channel = 'VAULT_TPM' AND object_guid IS NULL)
    );

-- The "one ACTIVE cert per device" invariant is GLOBAL across channels (Inv-1): a
-- pre-bound AD_CS device upgrading to TPM-native must revoke its active AD_CS cert
-- before the VAULT_TPM row inserts. Keep uq_endpoint_machine_certs_device_active
-- (from V11) unchanged — it already enforces this regardless of channel.

-- Replace the GLOBAL san_uri active-unique (V11) with channel-scoped variants:
--   * AD_CS  stays GLOBAL — an AD objectGUID is globally unique by construction, so a
--            cross-tenant duplicate adcomputer: SAN is a misissue and must collide.
--   * VAULT_TPM is TENANT-SCOPED (P0-3) — the same physical TPM may legitimately enroll
--            in two tenants and must yield two independent devices, never a cross-tenant
--            lock-out. The active uniqueness is therefore (tenant_id, san_uri).
DROP INDEX uq_endpoint_machine_certs_san_uri_active;

CREATE UNIQUE INDEX uq_endpoint_machine_certs_san_uri_active_adcs
    ON endpoint_machine_certs (san_uri)
    WHERE channel = 'AD_CS' AND revoked_at IS NULL;

CREATE UNIQUE INDEX uq_endpoint_machine_certs_san_uri_active_tpm
    ON endpoint_machine_certs (tenant_id, san_uri)
    WHERE channel = 'VAULT_TPM' AND revoked_at IS NULL;
