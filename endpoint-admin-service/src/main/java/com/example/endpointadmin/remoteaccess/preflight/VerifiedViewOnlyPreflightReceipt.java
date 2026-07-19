package com.example.endpointadmin.remoteaccess.preflight;

import java.time.Instant;

/** Runtime-attestor DSSE receipt after schema, trust-root, digest and replay verification. */
public record VerifiedViewOnlyPreflightReceipt(
        String envelopeSha256,
        String bindingSha256,
        String transactionIdSha256,
        Instant issuedAt,
        Instant expiresAt,
        int mutationCount,
        boolean attendedConsentAttempted) {

    public VerifiedViewOnlyPreflightReceipt {
        ViewOnlyDigest.requireSha256(envelopeSha256, "preflight envelopeSha256");
        ViewOnlyDigest.requireSha256(bindingSha256, "preflight bindingSha256");
        ViewOnlyDigest.requireSha256(transactionIdSha256, "preflight transactionIdSha256");
        if (issuedAt == null || expiresAt == null || !issuedAt.isBefore(expiresAt)
                || mutationCount != 0 || attendedConsentAttempted) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.CONTRACT_INVALID,
                    "preflight receipt must prove a bounded zero-mutation, no-consent observation");
        }
    }
}
