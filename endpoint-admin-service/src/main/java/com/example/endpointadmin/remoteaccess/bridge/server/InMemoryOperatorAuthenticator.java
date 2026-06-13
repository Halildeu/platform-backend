package com.example.endpointadmin.remoteaccess.bridge.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Faz 22.6 slice-4c-1 (Codex 019ebe06) — the in-memory REFERENCE operator authenticator (B1.3a / d-stepup-1
 * precedent). Deterministic + fail-closed, but a PLACEHOLDER trust basis: it compares the presented bearer
 * token to a single configured token (constant-time) — it does NOT validate a real JWT against an IdP nor an
 * mTLS client-cert chain against an operator CA. Those are later slices; this reference exists so the
 * operator transport (slice-4c-2) is testable and fail-closed before the live operator channel exists, and
 * the factory (slice-4c-1b) MUST forbid it in a prod-like profile.
 */
public final class InMemoryOperatorAuthenticator implements OperatorAuthenticator {

    private final String expectedBearerToken;
    private final String operatorSubject;
    private final String operatorTenantId;

    /**
     * @param expectedBearerToken the single token a reference operator must present (non-blank — a blank token
     *                            would authenticate nothing meaningful)
     * @param operatorSubject     the subject a successful reference authentication asserts (non-blank)
     * @param operatorTenantId    the tenant a successful reference authentication asserts (non-blank — Faz
     *                            22.6 slice-4c-2b-0, the tenancy boundary the device→peer resolver enforces; a
     *                            real authenticator derives this from a JWT claim or the operator cert)
     */
    public InMemoryOperatorAuthenticator(String expectedBearerToken, String operatorSubject,
                                         String operatorTenantId) {
        if (expectedBearerToken == null || expectedBearerToken.isBlank()) {
            throw new IllegalArgumentException("expectedBearerToken must be non-blank for the reference authenticator");
        }
        if (operatorSubject == null || operatorSubject.isBlank()) {
            throw new IllegalArgumentException("operatorSubject must be non-blank for the reference authenticator");
        }
        if (operatorTenantId == null || operatorTenantId.isBlank()) {
            throw new IllegalArgumentException("operatorTenantId must be non-blank for the reference authenticator");
        }
        this.expectedBearerToken = expectedBearerToken;
        this.operatorSubject = operatorSubject;
        this.operatorTenantId = operatorTenantId;
    }

    @Override
    public OperatorIdentity authenticate(OperatorCredential credential) {
        if (credential == null) {
            return OperatorIdentity.unauthenticated();
        }
        String token = credential.bearerToken().orElse(null);
        if (token == null || token.isBlank()) {
            return OperatorIdentity.unauthenticated();
        }
        // PLACEHOLDER trust basis — a constant-time compare to a configured token, NOT real JWT/IdP or mTLS
        // chain validation (that is the slice-4c real authenticator). A mismatch is fail-closed.
        if (!constantTimeEquals(token, expectedBearerToken)) {
            return OperatorIdentity.unauthenticated();
        }
        return OperatorIdentity.of(operatorTenantId, operatorSubject, AuthMethod.JWT_BEARER);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
