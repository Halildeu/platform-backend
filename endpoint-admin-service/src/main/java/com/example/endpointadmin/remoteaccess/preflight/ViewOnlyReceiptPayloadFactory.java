package com.example.endpointadmin.remoteaccess.preflight;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Exact schema-shaped lease and external-checkpoint receipt payload builder. */
public final class ViewOnlyReceiptPayloadFactory {
    public static final String LEASE_PAYLOAD_TYPE =
            "application/vnd.acik.faz22-6-view-only-checkpoint-lease.v1+json";
    public static final String CHECKPOINT_PAYLOAD_TYPE =
            "application/vnd.acik.faz22-6-view-only-external-checkpoint-receipt.v1+json";

    private final RemoteViewJsonCanonicalizer canonicalizer;

    public ViewOnlyReceiptPayloadFactory(RemoteViewJsonCanonicalizer canonicalizer) {
        this.canonicalizer = canonicalizer;
    }

    public ObjectNode lease(ViewOnlyLeaseSigningInput input) {
        ViewOnlyLeaseRedeemCommand command = input.command();
        ObjectNode payload = canonicalizer.mapper().createObjectNode();
        payload.put("schemaVersion", "faz22.6.viewOnlyCheckpointLease.v1");
        payload.put("leaseId", input.leaseId().toString());
        payload.put("redeemRequestId", command.requestId().toString());
        payload.put("idempotencyKeySha256", command.idempotencyKeySha256());
        payload.put("transactionIdSha256", command.transactionIdSha256());
        payload.put("bindingSha256", command.bindingSha256());
        payload.set("binding", command.binding().deepCopy());
        payload.put("evaluationPreflightReceiptEnvelopeSha256",
                command.evaluationPreflight().envelopeSha256());
        payload.put("redemptionPreflightReceiptEnvelopeSha256",
                input.redemptionPreflight().envelopeSha256());
        payload.put("redemptionPreflightIssuedAt", input.redemptionPreflight().issuedAt().toString());
        payload.put("authorizationEnvelopeSha256", command.authorization().envelopeSha256());
        payload.put("authorizationPayloadType", command.authorization().payloadType());
        payload.put("authorizationRedemptionCount", 1);
        payload.set("authorizationCaller", input.authorizationCaller().receiptProjection(canonicalizer));
        ObjectNode executor = payload.putObject("executorProfile");
        executor.put("audience", ViewOnlyGithubOidcProfile.EXECUTOR.audience());
        executor.put("subject", "repo:Halildeu/platform-k8s-gitops:ref:" + input.authorizationCaller().ref());
        executor.put("runnerEnvironment", ViewOnlyGithubOidcProfile.EXECUTOR.runnerEnvironment());
        payload.put("issuedAt", input.issuedAt().toString());
        payload.put("expiresAt", input.expiresAt().toString());
        payload.put("sequenceMinimumInclusive", 0);
        payload.put("sequenceMaximumInclusive", 63);
        payload.put("maxWrites", 64);
        payload.put("closed", false);
        return payload;
    }

    public ObjectNode checkpoint(ViewOnlyCheckpointSigningInput input) {
        ViewOnlyCheckpointCommand command = input.command();
        ObjectNode payload = canonicalizer.mapper().createObjectNode();
        payload.put("schemaVersion", "faz22.6.viewOnlyExternalCheckpointReceipt.v1");
        payload.put("receiptId", input.receiptId().toString());
        payload.put("leaseId", command.leaseId().toString());
        payload.put("leaseEnvelopeSha256", command.leaseEnvelopeSha256());
        payload.put("transactionIdSha256", command.transactionIdSha256());
        payload.put("bindingSha256", command.bindingSha256());
        payload.set("binding", input.binding().deepCopy());
        payload.put("evaluationPreflightReceiptEnvelopeSha256",
                input.evaluationPreflightEnvelopeSha256());
        payload.put("redemptionPreflightReceiptEnvelopeSha256",
                input.redemptionPreflightEnvelopeSha256());
        payload.put("authorizationEnvelopeSha256", input.authorizationEnvelopeSha256());
        payload.put("sequence", command.sequence());
        if (command.previousState() == null) {
            payload.putNull("previousState");
        } else {
            payload.put("previousState", command.previousState().name());
        }
        payload.put("state", command.state().name());
        payload.put("reasonCode", command.reasonCode());
        payload.put("storedObjectSha256", command.storedObjectSha256());
        if (command.previousStoredObjectSha256() == null) {
            payload.putNull("previousStoredObjectSha256");
        } else {
            payload.put("previousStoredObjectSha256", command.previousStoredObjectSha256());
        }
        payload.put("localCheckpointSha256", command.localCheckpointSha256());
        payload.put("localPayloadSha256", command.localPayloadSha256());
        payload.put("idempotencyKeySha256", command.idempotencyKeySha256());
        payload.set("executorCaller", input.executorCaller().receiptProjection(canonicalizer));
        payload.put("createdAt", command.createdAt().toString());
        payload.put("expiresAt", input.expiresAt().toString());
        payload.put("terminal", command.terminal());
        payload.put("credentialMaterialStored", false);
        return payload;
    }
}
