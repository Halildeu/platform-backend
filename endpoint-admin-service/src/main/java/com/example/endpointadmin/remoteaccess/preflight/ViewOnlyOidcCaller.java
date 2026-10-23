package com.example.endpointadmin.remoteaccess.preflight;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Verified, non-secret GitHub OIDC identity projection. Raw JWT material is never retained. */
public record ViewOnlyOidcCaller(
        String profile,
        String issuer,
        String subject,
        long actorId,
        long repositoryId,
        long runId,
        int runAttempt,
        String ref,
        String headSha,
        String tokenJtiSha256) {

    public ViewOnlyOidcCaller {
        require(profile, "profile");
        require(issuer, "issuer");
        require(subject, "subject");
        require(ref, "ref");
        if (actorId < 1 || repositoryId < 1 || runId < 1 || runAttempt != 1) {
            throw invalid("OIDC actor, repository, run and exact runAttempt=1 are required");
        }
        if (headSha == null || !headSha.matches("[0-9a-f]{40}")) {
            throw invalid("OIDC head SHA must be an exact lowercase Git SHA-1");
        }
        ViewOnlyDigest.requireSha256(tokenJtiSha256, "tokenJtiSha256");
    }

    public String stableIdentitySha256(RemoteViewJsonCanonicalizer canonicalizer) {
        return canonicalizer.digest(stableIdentityProjection(canonicalizer));
    }

    public ObjectNode stableIdentityProjection(RemoteViewJsonCanonicalizer canonicalizer) {
        ObjectNode identity = canonicalizer.mapper().createObjectNode();
        identity.put("iss", issuer);
        identity.put("sub", subject);
        identity.put("actor_id", Long.toString(actorId));
        identity.put("repository_id", Long.toString(repositoryId));
        identity.put("run_id", Long.toString(runId));
        identity.put("run_attempt", Integer.toString(runAttempt));
        identity.put("ref", ref);
        identity.put("sha", headSha);
        return identity;
    }

    public ObjectNode receiptProjection(RemoteViewJsonCanonicalizer canonicalizer) {
        ObjectNode caller = canonicalizer.mapper().createObjectNode();
        caller.put("profile", profile);
        caller.put("subject", subject);
        caller.put("runId", runId);
        caller.put("runAttempt", runAttempt);
        caller.put("headSha", headSha);
        caller.put("tokenJtiSha256", tokenJtiSha256);
        return caller;
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required");
        }
    }

    private static ViewOnlyAuthorityException invalid(String message) {
        return new ViewOnlyAuthorityException(ViewOnlyAuthorityError.CONTRACT_INVALID, message);
    }
}
