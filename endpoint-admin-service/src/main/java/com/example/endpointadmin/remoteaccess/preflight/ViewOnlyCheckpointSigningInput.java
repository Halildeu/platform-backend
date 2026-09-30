package com.example.endpointadmin.remoteaccess.preflight;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** Server-derived payload material passed to the Transit-backed receipt signer. */
public record ViewOnlyCheckpointSigningInput(
        UUID receiptId,
        ViewOnlyCheckpointCommand command,
        JsonNode binding,
        String evaluationPreflightEnvelopeSha256,
        String redemptionPreflightEnvelopeSha256,
        String authorizationEnvelopeSha256,
        ViewOnlyOidcCaller executorCaller,
        Instant expiresAt) {

    public ViewOnlyCheckpointSigningInput {
        ViewOnlyOidcBinding.fromJson(binding);
        binding = binding.deepCopy();
    }

    @Override
    public JsonNode binding() {
        return binding.deepCopy();
    }
}
