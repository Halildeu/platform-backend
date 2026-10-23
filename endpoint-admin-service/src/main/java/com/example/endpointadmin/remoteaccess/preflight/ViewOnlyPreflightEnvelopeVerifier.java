package com.example.endpointadmin.remoteaccess.preflight;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** Verifies a runtime-attestor DSSE preflight envelope against its independent trust root. */
@FunctionalInterface
public interface ViewOnlyPreflightEnvelopeVerifier {
    VerifiedViewOnlyPreflightReceipt verifyEvaluation(JsonNode envelope, Instant now);
}
