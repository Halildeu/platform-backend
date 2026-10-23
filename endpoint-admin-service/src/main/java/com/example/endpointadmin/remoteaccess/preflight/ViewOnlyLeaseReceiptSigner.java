package com.example.endpointadmin.remoteaccess.preflight;

/** No-fallback Transit signing boundary for checkpoint leases. */
@FunctionalInterface
public interface ViewOnlyLeaseReceiptSigner {
    byte[] sign(ViewOnlyLeaseSigningInput input);
}
