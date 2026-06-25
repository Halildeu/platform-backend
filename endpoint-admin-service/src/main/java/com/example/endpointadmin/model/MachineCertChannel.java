package com.example.endpointadmin.model;

/**
 * Faz 22.6 #548 Phase 1.5 — the enrollment channel that produced an
 * {@link EndpointMachineCert} row. The channel decides the SAN form + identity
 * semantics and is enforced by the {@code ck_endpoint_machine_certs_channel_object_guid}
 * CHECK (AD_CS requires an objectGUID; VAULT_TPM forbids one).
 */
public enum MachineCertChannel {

    /** ADR-0029 AD CS self-enrollment: SAN {@code adcomputer:{objectGUID}}, object_guid NOT NULL. */
    AD_CS,

    /** Faz 22.6 #548 TPM-native device-completion: SAN {@code tpm:{ek_pub_sha256}}, object_guid NULL. */
    VAULT_TPM
}
