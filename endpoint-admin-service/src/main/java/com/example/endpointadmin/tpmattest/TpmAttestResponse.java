package com.example.endpointadmin.tpmattest;

/**
 * Faz 22.3B (ADR-0039) gate-4d-2 — the {@code POST /enroll/tpm/attest} success response (L4):
 * the Vault-PKI-issued client certificate (PEM) for the attested, TPM-resident device key.
 * Returned ONLY after V1/V10/V5/V6/V4/V9 all pass and Vault signs. Every failure is the uniform 403.
 */
public record TpmAttestResponse(String certificate) {
}
