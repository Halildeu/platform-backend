package com.example.endpointadmin.remoteaccess.preflight;

/** Vault Transit boundary. Returns only the raw 64-byte Ed25519 signature. */
@FunctionalInterface
public interface ViewOnlyTransitSigningClient {
    byte[] sign(byte[] dssePae);

    /** Public-metadata-only startup/readiness check; implementations fail closed by default. */
    default void probeReady() {
        throw new ViewOnlyAuthorityException(
                ViewOnlyAuthorityError.SIGNING_UNAVAILABLE,
                "Transit signing dependency does not implement a readiness probe");
    }
}
