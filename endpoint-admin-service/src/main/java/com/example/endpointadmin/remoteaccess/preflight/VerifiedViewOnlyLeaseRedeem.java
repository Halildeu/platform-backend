package com.example.endpointadmin.remoteaccess.preflight;

/** Fully verified authorization-profile lease redemption. */
public record VerifiedViewOnlyLeaseRedeem(
        ViewOnlyLeaseRedeemCommand command,
        ViewOnlyOidcCaller caller) {
}
