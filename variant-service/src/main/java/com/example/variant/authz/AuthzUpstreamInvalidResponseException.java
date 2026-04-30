package com.example.variant.authz;

/**
 * Codex 019dddb7 iter-42 — variant-service authz upstream classification.
 *
 * <p>Thrown when permission-service returned 2xx but the body is empty,
 * malformed, or missing required identity fields (userId, email). This is
 * a contract violation between two services — neither a network failure
 * (which would be {@link AuthzDependencyUnavailableException}) nor a
 * client-side authentication issue (which would be 401). Maps to HTTP 502.
 */
public class AuthzUpstreamInvalidResponseException extends RuntimeException {

    public AuthzUpstreamInvalidResponseException(String message, Throwable cause) {
        super(message, cause);
    }

    public AuthzUpstreamInvalidResponseException(String message) {
        super(message);
    }
}
