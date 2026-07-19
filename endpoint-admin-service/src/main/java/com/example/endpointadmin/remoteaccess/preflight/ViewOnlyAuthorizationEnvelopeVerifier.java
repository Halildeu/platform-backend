package com.example.endpointadmin.remoteaccess.preflight;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** Verifies the #2502 v3 authority envelope, grant, transaction binding and revocation state. */
@FunctionalInterface
public interface ViewOnlyAuthorizationEnvelopeVerifier {
    VerifiedViewOnlyAuthorization verify(JsonNode envelope, Instant now);
}
