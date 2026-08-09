package com.example.endpointadmin.remoteaccess.preflight;

import java.time.Instant;
import java.util.UUID;

/** Strict checkpoint-create request after schema, lease-envelope and OIDC verification. */
public record ViewOnlyCheckpointCommand(
        UUID requestId,
        UUID leaseId,
        String leaseEnvelopeSha256,
        String transactionIdSha256,
        String bindingSha256,
        int sequence,
        ViewOnlyCheckpointState previousState,
        ViewOnlyCheckpointState state,
        String reasonCode,
        String localCheckpointSha256,
        String localPayloadSha256,
        String previousStoredObjectSha256,
        String storedObjectSha256,
        String idempotencyKeySha256,
        String requestBodySha256,
        String executorIdentitySha256,
        boolean terminal,
        Instant createdAt) {

    public ViewOnlyCheckpointCommand {
        if (requestId == null || leaseId == null || state == null || createdAt == null) {
            throw invalid("request, lease, state and creation time are required");
        }
        ViewOnlyDigest.requireSha256(leaseEnvelopeSha256, "leaseEnvelopeSha256");
        ViewOnlyDigest.requireSha256(transactionIdSha256, "transactionIdSha256");
        ViewOnlyDigest.requireSha256(bindingSha256, "bindingSha256");
        ViewOnlyDigest.requireSha256(localCheckpointSha256, "localCheckpointSha256");
        ViewOnlyDigest.requireSha256(localPayloadSha256, "localPayloadSha256");
        if (previousStoredObjectSha256 != null) {
            ViewOnlyDigest.requireSha256(previousStoredObjectSha256, "previousStoredObjectSha256");
        }
        ViewOnlyDigest.requireSha256(storedObjectSha256, "storedObjectSha256");
        ViewOnlyDigest.requireSha256(idempotencyKeySha256, "idempotencyKeySha256");
        ViewOnlyDigest.requireSha256(requestBodySha256, "requestBodySha256");
        ViewOnlyDigest.requireSha256(executorIdentitySha256, "executorIdentitySha256");
        if (sequence < 0 || sequence > 63) {
            throw invalid("checkpoint sequence must be between 0 and 63");
        }
        if (reasonCode == null || !reasonCode.matches("[a-z0-9][a-z0-9-]{1,63}")) {
            throw invalid("checkpoint reason code is invalid");
        }
        if (sequence == 0) {
            ViewOnlyCheckpointStateMachine.validateInitial(sequence, previousState, state, terminal);
            if (previousStoredObjectSha256 != null) {
                throw invalid("initial checkpoint must not name a previous stored object");
            }
        } else if (previousState == null || previousStoredObjectSha256 == null) {
            throw invalid("non-initial checkpoint requires previous state and stored object digest");
        }
    }

    private static ViewOnlyAuthorityException invalid(String message) {
        return new ViewOnlyAuthorityException(ViewOnlyAuthorityError.CONTRACT_INVALID, message);
    }
}
