package com.example.endpointadmin.remoteaccess.preflight;

/** Fully verified checkpoint-create material accepted by the durable CAS. */
public record VerifiedViewOnlyCheckpointCreate(
        ViewOnlyCheckpointCommand command,
        ViewOnlyOidcCaller caller) {
}
