package com.example.endpointadmin.remoteaccess.preflight;

/**
 * Reads a short-lived Vault token at request time. Implementations must never
 * log, cache or expose the returned credential and callers must clear it.
 */
@FunctionalInterface
public interface ViewOnlyVaultTokenSource {
    char[] readToken();
}
