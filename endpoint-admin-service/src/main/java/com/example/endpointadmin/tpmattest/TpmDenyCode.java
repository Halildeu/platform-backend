package com.example.endpointadmin.tpmattest;

/**
 * Faz 22.3B (ADR-0039) gate-4 — TPM attestation verifier deny codes (verifier
 * checks V1–V12, design §4 + §10.5).
 *
 * <p><b>Audit-only.</b> These codes are recorded in the append-only attestation
 * audit log; they are NEVER returned on the wire. Every enrollment deny is a
 * single uniform {@code 403} + fixed body (design §9) to avoid a
 * behavioral/enumeration oracle.
 */
public enum TpmDenyCode {
    /** V1 — nonce missing / expired / already consumed / clock-skew / wrong scope. */
    NONCE_INVALID,
    /** V2 — EK cert does not chain to a trusted manufacturer root, is expired, revoked, or ROCA-class. */
    EK_UNTRUSTED,
    /** V3 — AK is not bound to the presented EK. */
    AK_BINDING_FAILED,
    /** V4 — CSR public key is not the TPM-resident key certified by the AK. */
    KEY_NOT_TPM_BOUND,
    /** V5 — quote signature/structure invalid, or not over the issued nonce. */
    QUOTE_INVALID,
    /** V6 — PCR policy not satisfied (mandatory for HIGH-risk classes). */
    PCR_POLICY_FAILED,
    /** V7 — device not registered / disabled / decommissioned / revoked. */
    DEVICE_NOT_ELIGIBLE,
    /** V8 — feature flag off for tenant, or enrollment channel not TPM-Vault. */
    FEATURE_DISABLED,
    /** V9 — CSR key algorithm/size/hash below policy, or a critical extension beyond clientAuth. */
    CSR_POLICY_VIOLATION,
    /** V10 — credential-activation failed: recovered secret != server secret (EK↔AK↔one-TPM proof). */
    ACTIVATION_FAILED,
    /** V11 — AK is not a restricted signing key (TPMA_OBJECT restricted+sign+fixedTPM+sensitiveDataOrigin), or AK-name mismatch. */
    AK_NOT_RESTRICTED,
    /** V12 — a key or signature uses a non-whitelisted algorithm (weak/confused). */
    WEAK_ALGORITHM,
    /** Catch-all for a malformed envelope that fails before a specific check. */
    MALFORMED_ENVELOPE
}
