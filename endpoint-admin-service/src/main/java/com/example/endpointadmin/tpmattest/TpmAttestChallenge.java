package com.example.endpointadmin.tpmattest;

import java.time.Instant;

/**
 * Faz 22.3B (ADR-0039) gate-4 — the {@code POST /enroll/tpm/nonce} response (L1):
 * the nonce (quote freshness) + the TPM2_MakeCredential challenge the device must
 * solve with TPM2_ActivateCredential (design §2, §9). All binary fields base64.
 *
 * <p>The matching {@code serverSecret} is NOT returned — it lives only in the
 * {@link TpmNonceStore} and is recovered by the device via ActivateCredential,
 * then echoed as {@code activatedSecret} for V10.
 */
public record TpmAttestChallenge(
        String nonceId,
        String nonce,
        Instant exp,
        String credBlob,
        String encSecret) {
}
