package com.example.endpointadmin.remoteaccess.preflight;

import java.time.Instant;

/** Cross-AI v3 machine-authority envelope after signature, grant and revocation verification. */
public record VerifiedViewOnlyAuthorization(
        String envelopeSha256,
        String payloadType,
        String bindingSha256,
        String transactionIdSha256,
        Instant issuedAt,
        Instant expiresAt,
        boolean revoked) {

    public static final String PAYLOAD_TYPE = "application/vnd.acik.cross-ai-deployment-bundle.v3+json";

    public VerifiedViewOnlyAuthorization {
        ViewOnlyDigest.requireSha256(envelopeSha256, "authorization envelopeSha256");
        ViewOnlyDigest.requireSha256(bindingSha256, "authorization bindingSha256");
        ViewOnlyDigest.requireSha256(transactionIdSha256, "authorization transactionIdSha256");
        if (!PAYLOAD_TYPE.equals(payloadType) || issuedAt == null || expiresAt == null
                || !issuedAt.isBefore(expiresAt) || revoked) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.CONTRACT_INVALID,
                    "authorization envelope is not an active v3 machine authority");
        }
    }
}
