package com.example.endpointadmin.remoteaccess.preflight;

import java.util.UUID;

/** Structurally and OIDC-verified material allowed to query a committed lease response. */
public record ViewOnlyLeaseRetryCandidate(
        UUID requestId,
        String idempotencyKeySha256,
        String requestBodySha256,
        String callerIdentitySha256,
        String authorizationEnvelopeSha256,
        String transactionIdSha256) {
}
