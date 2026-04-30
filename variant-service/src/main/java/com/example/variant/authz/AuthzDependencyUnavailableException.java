package com.example.variant.authz;

/**
 * Codex 019dddb7 iter-42 — variant-service authz upstream classification.
 *
 * <p>Thrown when permission-service is unreachable or returns 5xx. The
 * caller's JWT may be perfectly valid; the dependency itself is degraded.
 * Maps to HTTP 503 in the REST exception handler so the frontend can
 * surface a transient-error UX instead of mis-classifying it as a session
 * expiry (which is what the legacy 401 produced — see
 * {@link com.example.variant.controller.VariantControllerV1} pre-iter-42).
 */
public class AuthzDependencyUnavailableException extends RuntimeException {

    public AuthzDependencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public AuthzDependencyUnavailableException(String message) {
        super(message);
    }
}
