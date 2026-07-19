package com.example.endpointadmin.remoteaccess.preflight;

/** Vault Transit boundary. Returns only the raw 64-byte Ed25519 signature. */
@FunctionalInterface
public interface ViewOnlyTransitSigningClient {
    byte[] sign(byte[] dssePae);
}
