package com.example.endpointadmin.remoteaccess.preflight;

import java.util.UUID;

/** Stable HTTP/status mapping; store, signing and absence failures are never conflated. */
public final class ViewOnlyAuthorityHttpErrorMapper {

    public MappedError map(ViewOnlyAuthorityException failure) {
        Mapping mapping = switch (failure.reason()) {
            case CONTRACT_INVALID -> new Mapping(400, "REQUEST_SCHEMA_INVALID", false);
            case IDEMPOTENCY_CONFLICT -> new Mapping(409, "CHECKPOINT_IDEMPOTENCY_CONFLICT", false);
            case AUTHORITY_CONSUMED -> new Mapping(409, "AUTHORIZATION_REPLAYED", false);
            case LEASE_NOT_FOUND -> new Mapping(403, "LEASE_BINDING_MISMATCH", false);
            case CHECKPOINT_NOT_FOUND -> new Mapping(404, "CHECKPOINT_NOT_FOUND", false);
            case LEASE_EXPIRED -> new Mapping(410, "CHECKPOINT_LEASE_EXPIRED", false);
            case LEASE_CLOSED, WRITE_LIMIT_EXCEEDED -> new Mapping(410, "CHECKPOINT_LEASE_CLOSED", false);
            case LEASE_BINDING_MISMATCH -> new Mapping(403, "LEASE_BINDING_MISMATCH", false);
            case EXECUTOR_IDENTITY_MISMATCH -> new Mapping(403, "OIDC_CLAIM_MISMATCH", false);
            case SEQUENCE_CONFLICT -> new Mapping(409, "CHECKPOINT_SEQUENCE_CONFLICT", false);
            case PREVIOUS_CHECKPOINT_MISMATCH ->
                    new Mapping(409, "CHECKPOINT_PREVIOUS_DIGEST_MISMATCH", false);
            case STATE_TRANSITION_DENIED, TERMINAL_FLAG_INVALID ->
                    new Mapping(409, "CHECKPOINT_STATE_INVALID", false);
            case SIGNING_UNAVAILABLE -> new Mapping(503, "SIGNING_UNAVAILABLE", true);
            case CHECKPOINT_STORE_UNAVAILABLE ->
                    new Mapping(503, "CHECKPOINT_STORE_UNAVAILABLE", true);
        };
        return new MappedError(mapping.status(), new ViewOnlyAuthorityErrorResponse(
                "faz22.6.viewOnlyPreflightError.v1", UUID.randomUUID(), mapping.code(),
                safeMessage(failure.getMessage()), mapping.retryable(), 0, false));
    }

    private static String safeMessage(String value) {
        if (value == null || value.isBlank()) {
            return "VIEW_ONLY authority request failed closed";
        }
        StringBuilder safe = new StringBuilder(Math.min(value.length(), 256));
        value.codePoints().limit(256).forEach(codePoint ->
                safe.append(codePoint >= 0x20 && codePoint <= 0x7e ? (char) codePoint : '?'));
        return safe.toString();
    }

    private record Mapping(int status, String code, boolean retryable) {
    }

    public record MappedError(int httpStatus, ViewOnlyAuthorityErrorResponse body) {
    }
}
