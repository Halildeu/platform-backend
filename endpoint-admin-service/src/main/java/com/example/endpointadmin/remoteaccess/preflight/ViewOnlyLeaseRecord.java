package com.example.endpointadmin.remoteaccess.preflight;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** Verified and signed checkpoint lease material ready for one atomic DB redemption. */
public record ViewOnlyLeaseRecord(
        UUID leaseId,
        UUID redeemRequestId,
        String idempotencyKeySha256,
        String requestBodySha256,
        String callerIdentitySha256,
        String transactionIdSha256,
        String bindingSha256,
        JsonNode binding,
        String leaseEnvelopeSha256,
        String evaluationPreflightEnvelopeSha256,
        String redemptionPreflightEnvelopeSha256,
        String authorizationEnvelopeSha256,
        byte[] signedLeaseEnvelope,
        Instant issuedAt,
        Instant expiresAt,
        int maxWrites) {

    public ViewOnlyLeaseRecord {
        if (leaseId == null || redeemRequestId == null || binding == null || issuedAt == null || expiresAt == null) {
            throw invalid("lease identity, binding and lifetime are required");
        }
        ViewOnlyDigest.requireSha256(idempotencyKeySha256, "idempotencyKeySha256");
        ViewOnlyDigest.requireSha256(requestBodySha256, "requestBodySha256");
        ViewOnlyDigest.requireSha256(callerIdentitySha256, "callerIdentitySha256");
        ViewOnlyDigest.requireSha256(transactionIdSha256, "transactionIdSha256");
        ViewOnlyDigest.requireSha256(bindingSha256, "bindingSha256");
        ViewOnlyDigest.requireSha256(leaseEnvelopeSha256, "leaseEnvelopeSha256");
        ViewOnlyDigest.requireSha256(evaluationPreflightEnvelopeSha256, "evaluationPreflightEnvelopeSha256");
        ViewOnlyDigest.requireSha256(redemptionPreflightEnvelopeSha256, "redemptionPreflightEnvelopeSha256");
        ViewOnlyDigest.requireSha256(authorizationEnvelopeSha256, "authorizationEnvelopeSha256");
        if (signedLeaseEnvelope == null || signedLeaseEnvelope.length == 0 || signedLeaseEnvelope.length > 1_048_576) {
            throw invalid("signed lease envelope must be between 1 byte and 1 MiB");
        }
        signedLeaseEnvelope = signedLeaseEnvelope.clone();
        if (!issuedAt.isBefore(expiresAt) || maxWrites != 64) {
            throw invalid("lease lifetime and exact maxWrites=64 are required");
        }
    }

    @Override
    public byte[] signedLeaseEnvelope() {
        return signedLeaseEnvelope.clone();
    }

    private static ViewOnlyAuthorityException invalid(String message) {
        return new ViewOnlyAuthorityException(ViewOnlyAuthorityError.CONTRACT_INVALID, message);
    }
}
