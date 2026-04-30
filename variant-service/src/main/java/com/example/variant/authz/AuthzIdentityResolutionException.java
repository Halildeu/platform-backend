package com.example.variant.authz;

/**
 * Codex 019dddb7 iter-42 — variant-service authz identity classification.
 *
 * <p>Thrown when the JWT itself was accepted but the local identity
 * invariants (e.g. numeric userId required by variant ownership writes)
 * cannot be satisfied. The token is valid, the upstream responded, but
 * the cross-service contract is broken at the identity layer — the
 * caller's session is fine, the system's wiring is not. Maps to HTTP 500
 * because re-authenticating the user would not fix the condition.
 */
public class AuthzIdentityResolutionException extends RuntimeException {

    public AuthzIdentityResolutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public AuthzIdentityResolutionException(String message) {
        super(message);
    }
}
