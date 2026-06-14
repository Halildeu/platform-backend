package com.example.endpointadmin.model;

public enum EnrollmentStatus {
    PENDING,
    CONSUMED,
    EXPIRED,
    REVOKED,
    // Faz 22.3B (ADR-0039) gate-4d-2 — TPM attestation enrollment state machine
    // (Codex 019ec723 REVISE#2): PENDING → TPM_IN_PROGRESS (set at /attest BEFORE the
    // external Vault call, so a re-/nonce PENDING lookup can't double-issue) → CONSUMED
    // (Vault success + device bind) | TPM_FAILED (verify/issue failure; NOT returned to
    // PENDING — bounds retries on a suspicious attest). Stored as the enum name in the
    // length-32 status column (fits; no schema change).
    TPM_IN_PROGRESS,
    TPM_FAILED
}
