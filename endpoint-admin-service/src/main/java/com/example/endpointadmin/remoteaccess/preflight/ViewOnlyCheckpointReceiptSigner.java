package com.example.endpointadmin.remoteaccess.preflight;

/** No-fallback signing boundary. A runtime bean exists only for a configured Vault Transit key. */
@FunctionalInterface
public interface ViewOnlyCheckpointReceiptSigner {
    byte[] sign(ViewOnlyCheckpointSigningInput input);
}
