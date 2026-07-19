package com.example.endpointadmin.remoteaccess.preflight;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class ViewOnlyTestFixtures {
    static final String REF = "refs/tags/cross-ai-intent/123e4567-e89b-42d3-a456-426614174000";
    static final String HEAD = "0123456789abcdef0123456789abcdef01234567";

    private ViewOnlyTestFixtures() {
    }

    static ObjectNode binding(RemoteViewJsonCanonicalizer canonicalizer, long actorId, long runId) {
        String digest = "sha256:" + "1".repeat(64);
        return canonicalizer.mapper().createObjectNode()
                .put("repositoryId", 1211415632L)
                .put("repository", "Halildeu/platform-k8s-gitops")
                .put("environment", "faz22-view-only-pilot")
                .put("deploymentClass", "reversible-test")
                .put("productSlice", "Halildeu/platform-k8s-gitops#2373")
                .put("intentRef", REF)
                .put("intentBundleSha256", digest)
                .put("transactionSessionSha256", digest)
                .put("headSha", HEAD)
                .put("workflowPath", ViewOnlyGithubOidcValidator.WORKFLOW_PATH)
                .put("workflowRef", "Halildeu/platform-k8s-gitops/"
                        + ViewOnlyGithubOidcValidator.WORKFLOW_PATH + "@" + REF)
                .put("workflowBlobSha256", digest)
                .put("dependencyLockSha256", digest)
                .put("concurrencySha256", digest)
                .put("authoritySetSha256", digest)
                .put("runId", runId)
                .put("runAttempt", 1)
                .put("triggeringActorId", actorId)
                .put("machineAuthorityPolicySha256", digest)
                .put("artifactSetSha256", digest)
                .put("rollbackPlanSha256", digest)
                .put("postDeployVerifierSha256", digest)
                .put("bootstrapCredentialSha256", digest)
                .put("tenantIdSha256", digest)
                .put("preflightPersonaIdentitySha256", digest)
                .put("endpointIdSha256", digest)
                .put("operatorIdSha256", digest)
                .put("deviceHostnameSha256", digest)
                .put("attendedConsentPolicySha256", digest)
                .put("pilotOwnerPolicySha256", digest)
                .put("maskPolicySha256", digest)
                .put("runtimeImageDigest", digest)
                .put("pilotSeconds", 900)
                .put("transactionScopeSha256", digest)
                .put("runnerPolicySha256", digest)
                .put("runnerAdmissionLeaseSha256", digest);
    }
}
