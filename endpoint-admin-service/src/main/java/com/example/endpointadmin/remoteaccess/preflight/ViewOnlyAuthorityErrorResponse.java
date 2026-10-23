package com.example.endpointadmin.remoteaccess.preflight;

import java.util.UUID;

/** Exact non-secret error response shared by attestor, lease and checkpoint routes. */
public record ViewOnlyAuthorityErrorResponse(
        String schemaVersion,
        UUID errorId,
        String code,
        String message,
        boolean retryable,
        int mutationCount,
        boolean credentialMaterialIncluded) {

    public ViewOnlyAuthorityErrorResponse {
        if (!"faz22.6.viewOnlyPreflightError.v1".equals(schemaVersion)
                || errorId == null || code == null || code.isBlank()
                || message == null || message.isBlank() || message.length() > 256
                || mutationCount != 0 || credentialMaterialIncluded) {
            throw new IllegalArgumentException("invalid VIEW_ONLY authority error response");
        }
    }
}
