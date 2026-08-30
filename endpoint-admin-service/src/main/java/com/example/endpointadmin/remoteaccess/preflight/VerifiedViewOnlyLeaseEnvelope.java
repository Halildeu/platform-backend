package com.example.endpointadmin.remoteaccess.preflight;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** Trust-root and DSSE-verified checkpoint lease payload projection. */
public record VerifiedViewOnlyLeaseEnvelope(
        UUID leaseId,
        String leaseEnvelopeSha256,
        String transactionIdSha256,
        String bindingSha256,
        JsonNode binding,
        ViewOnlyOidcBinding oidcBinding,
        String evaluationPreflightEnvelopeSha256,
        String redemptionPreflightEnvelopeSha256,
        String authorizationEnvelopeSha256,
        Instant expiresAt,
        boolean closed,
        int sequenceMinimumInclusive,
        int sequenceMaximumInclusive,
        int maxWrites) {

    public VerifiedViewOnlyLeaseEnvelope {
        if (leaseId == null || binding == null || oidcBinding == null || expiresAt == null) {
            throw invalid("verified lease identity, binding and expiry are required");
        }
        ViewOnlyOidcBinding parsedBinding = ViewOnlyOidcBinding.fromJson(binding);
        if (!parsedBinding.equals(oidcBinding)) {
            throw invalid("verified lease OIDC binding projection does not match exact binding");
        }
        binding = binding.deepCopy();
        ViewOnlyDigest.requireSha256(leaseEnvelopeSha256, "leaseEnvelopeSha256");
        ViewOnlyDigest.requireSha256(transactionIdSha256, "transactionIdSha256");
        ViewOnlyDigest.requireSha256(bindingSha256, "bindingSha256");
        ViewOnlyDigest.requireSha256(evaluationPreflightEnvelopeSha256, "evaluationPreflightEnvelopeSha256");
        ViewOnlyDigest.requireSha256(redemptionPreflightEnvelopeSha256, "redemptionPreflightEnvelopeSha256");
        ViewOnlyDigest.requireSha256(authorizationEnvelopeSha256, "authorizationEnvelopeSha256");
        if (sequenceMinimumInclusive != 0 || sequenceMaximumInclusive != 63 || maxWrites != 64) {
            throw invalid("verified lease must authorize exact sequence 0..63 and maxWrites=64");
        }
    }

    @Override
    public JsonNode binding() {
        return binding.deepCopy();
    }

    private static ViewOnlyAuthorityException invalid(String message) {
        return new ViewOnlyAuthorityException(ViewOnlyAuthorityError.CONTRACT_INVALID, message);
    }
}
