package com.example.endpointadmin.remoteaccess.preflight;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/** Strict lease-redeem request after body/schema/envelope/OIDC verification. */
public record ViewOnlyLeaseRedeemCommand(
        UUID requestId,
        String idempotencyKeySha256,
        String requestBodySha256,
        String callerIdentitySha256,
        JsonNode binding,
        String bindingSha256,
        String transactionIdSha256,
        VerifiedViewOnlyPreflightReceipt evaluationPreflight,
        VerifiedViewOnlyAuthorization authorization,
        int requestedTtlSeconds,
        int requestedMaxWrites) {
}
