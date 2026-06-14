package com.example.endpointadmin.tpmattest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Faz 22.3B (ADR-0039) gate-4d — the {@code POST /api/v1/agent/enrollments/tpm/nonce} (L1) body.
 * All binary fields are base64. {@code @Size} caps bound the request (defense-in-depth alongside the
 * controller body-size limit). Path-free: opaque token + TPM public material only.
 *
 * <ul>
 *   <li>{@code enrollmentToken} — the bootstrap token; the scope (tenant/device) is server-derived
 *       from it (never trusted as caller input).</li>
 *   <li>{@code ekCert} (+ optional {@code ekCertChain}) — the EK certificate; V2 chains it to a
 *       pinned manufacturer root and binds its public key to {@code ekPub}.</li>
 *   <li>{@code ekPub} — the EK {@code TPM2B_PUBLIC} (gives the exact RSA key + nameAlg for the
 *       in-process MakeCredential challenge).</li>
 *   <li>{@code akPub}/{@code akName} — the AK public + its TPM Name (V11 + the L1→L2 binding).</li>
 * </ul>
 */
public record TpmNonceRequest(
        @NotBlank @Size(max = 512) String enrollmentToken,
        @NotBlank @Size(max = 8192) String ekCert,
        @Size(max = 16) List<@NotBlank @Size(max = 8192) String> ekCertChain,
        @NotBlank @Size(max = 4096) String ekPub,
        @NotBlank @Size(max = 4096) String akPub,
        @NotBlank @Size(max = 1024) String akName) {
}
