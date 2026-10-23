package com.example.endpointadmin.remoteaccess.preflight;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** Runtime trust-root verifier. No unsigned or local-key fallback is permitted. */
@FunctionalInterface
public interface ViewOnlyLeaseEnvelopeVerifier {
    VerifiedViewOnlyLeaseEnvelope verify(JsonNode envelope, Instant now);
}
