package com.example.endpointadmin.remoteaccess.preflight;

import java.util.UUID;

/** Structurally and OIDC-verified material allowed to query one committed checkpoint response. */
public record ViewOnlyCheckpointRetryCandidate(
        UUID requestId,
        String idempotencyKeySha256,
        String requestBodySha256,
        String executorIdentitySha256,
        String leaseEnvelopeSha256,
        String transactionIdSha256,
        int sequence,
        String storedObjectSha256) {
}
