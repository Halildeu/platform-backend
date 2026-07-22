package com.example.endpointadmin.remoteaccess.preflight;

import java.time.Instant;
import java.util.UUID;

/** Server-derived lease payload material passed to the Transit-backed checkpoint signer. */
public record ViewOnlyLeaseSigningInput(
        UUID leaseId,
        ViewOnlyLeaseRedeemCommand command,
        ViewOnlyOidcCaller authorizationCaller,
        VerifiedViewOnlyPreflightReceipt redemptionPreflight,
        Instant issuedAt,
        Instant expiresAt) {
}
