package com.example.endpointadmin.remoteaccess.preflight;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.security.oauth2.jwt.Jwt;

/** Converts a validated JWT into the non-secret receipt projection and enforces signed binding equality. */
public final class ViewOnlyOidcCallerFactory {
    private final RemoteViewJsonCanonicalizer canonicalizer;
    private final String jtiDigestDomain;

    public ViewOnlyOidcCallerFactory(RemoteViewJsonCanonicalizer canonicalizer, String jtiDigestDomain) {
        this.canonicalizer = canonicalizer;
        if (jtiDigestDomain == null || jtiDigestDomain.isBlank()) {
            throw new IllegalArgumentException("canonical JTI digest domain is required");
        }
        this.jtiDigestDomain = jtiDigestDomain;
    }

    public ViewOnlyOidcCaller create(Jwt jwt,
                                     ViewOnlyGithubOidcProfile profile,
                                     ViewOnlyOidcBinding binding) {
        long actorId = positiveLong(jwt, "actor_id");
        long repositoryId = positiveLong(jwt, "repository_id");
        long runId = positiveLong(jwt, "run_id");
        int runAttempt = Math.toIntExact(positiveLong(jwt, "run_attempt"));
        String ref = text(jwt, "ref");
        String headSha = text(jwt, "sha");
        if (actorId != binding.triggeringActorId()
                || runId != binding.runId()
                || runAttempt != binding.runAttempt()
                || !ref.equals(binding.intentRef())
                || !headSha.equals(binding.headSha())) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.LEASE_BINDING_MISMATCH,
                    "GitHub OIDC run identity does not match the signed transaction binding");
        }
        ObjectNode jtiProjection = canonicalizer.mapper().createObjectNode();
        jtiProjection.put("domain", jtiDigestDomain);
        jtiProjection.put("issuer", jwt.getIssuer().toString());
        jtiProjection.put("audience", profile.audience());
        jtiProjection.put("subject", jwt.getSubject());
        jtiProjection.put("jti", text(jwt, "jti"));
        return new ViewOnlyOidcCaller(
                profile.receiptName(), jwt.getIssuer().toString(), jwt.getSubject(), actorId,
                repositoryId, runId, runAttempt, ref, headSha, canonicalizer.digest(jtiProjection));
    }

    private static long positiveLong(Jwt jwt, String claim) {
        try {
            long value = Long.parseLong(text(jwt, claim));
            if (value < 1) {
                throw new NumberFormatException();
            }
            return value;
        } catch (RuntimeException invalid) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.CONTRACT_INVALID, "verified OIDC numeric claim is invalid");
        }
    }

    private static String text(Jwt jwt, String claim) {
        Object value = jwt.getClaims().get(claim);
        if (value == null || value.toString().isBlank()) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.CONTRACT_INVALID, "verified OIDC claim is missing");
        }
        return value.toString();
    }
}
