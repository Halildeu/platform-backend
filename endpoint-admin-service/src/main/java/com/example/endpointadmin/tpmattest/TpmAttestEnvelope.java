package com.example.endpointadmin.tpmattest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Faz 22.3B (ADR-0039) gate-4 — the {@code POST /enroll/tpm/attest} body (L3,
 * design §3 envelope v2). All binary fields are base64-encoded DER / TPM
 * structures. Path-free: only opaque refs + TPM public material + PCR digests.
 *
 * <p>{@code activatedSecret} (envelope v2) is the secret the device recovered via
 * TPM2_ActivateCredential — the V10 EK↔AK binding proof. {@code certify*} prove
 * the CSR key is TPM-resident (V4); {@code quote*} prove freshness over the nonce
 * + PCR state (V5/V6); {@code csrDer} is the PKCS#10 whose <b>public key only</b>
 * is signed (SAN/CN backend-overridden).
 */
public record TpmAttestEnvelope(
        @NotBlank @Size(max = 64) String schema,
        // The bootstrap enrollment token (gate-4d-2): L2 re-derives the SAME server-side scope as L1
        // to consume the scope-bound nonce (anti device-hijack — V1).
        @NotBlank @Size(max = 512) String enrollmentToken,
        @NotBlank @Size(max = 256) String deviceRef,
        @NotBlank @Size(max = 128) String nonceId,
        @NotBlank @Size(max = 8192) String ekCert,
        @Size(max = 16) List<@NotBlank @Size(max = 8192) String> ekCertChain,
        @NotBlank @Size(max = 4096) String akPub,
        @NotBlank @Size(max = 1024) String akName,
        @NotBlank @Size(max = 1024) String activatedSecret,
        @NotBlank @Size(max = 4096) String certifyInfo,
        @NotBlank @Size(max = 2048) String certifySig,
        @NotBlank @Size(max = 4096) String quote,
        @NotBlank @Size(max = 2048) String quoteSig,
        Map<@NotBlank @Size(max = 16) String, Map<@NotBlank @Size(max = 8) String, @NotBlank @Size(max = 128) String>> pcrs,
        // The device key's TPM2B_PUBLIC (gate-4d-2): V4 recomputes its TPM Name to match the AK's
        // certify, and binds it to the CSR's public key (the attested key == the key being signed).
        @NotBlank @Size(max = 4096) String deviceKeyPub,
        @NotBlank @Size(max = 8192) String csrDer) {

    /** The envelope schema this verifier accepts. */
    public static final String SCHEMA_V2 = "faz22.3b.tpm-attest.v2";
}
