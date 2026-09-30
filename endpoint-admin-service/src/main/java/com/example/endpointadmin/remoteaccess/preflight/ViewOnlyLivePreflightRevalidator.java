package com.example.endpointadmin.remoteaccess.preflight;

import com.fasterxml.jackson.databind.JsonNode;

/** Executes the exact fixed twelve checks with zero configuration mutation at lease redemption. */
@FunctionalInterface
public interface ViewOnlyLivePreflightRevalidator {
    VerifiedViewOnlyPreflightReceipt revalidate(JsonNode binding, ViewOnlyOidcCaller authorizationCaller);
}
