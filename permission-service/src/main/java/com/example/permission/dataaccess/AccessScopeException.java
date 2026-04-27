package com.example.permission.dataaccess;

import java.time.Instant;

/**
 * Faz 21.3 PR-D: domain exceptions for the {@code data_access.scope} surface.
 * Each subclass carries an {@code errorCode} that the controller-advice maps
 * to a stable HTTP body field, plus the originating {@code scope_id} (where
 * applicable) for the response payload.
 *
 * <p>Mapping (see {@link com.example.permission.controller.AccessScopeExceptionHandler}):
 * <ul>
 *   <li>{@link ScopeNotFoundException} → 404</li>
 *   <li>{@link ScopeAlreadyRevokedException} → 409</li>
 *   <li>{@link ScopeAlreadyGrantedException} → 409 (active duplicate)</li>
 *   <li>{@link ScopeValidationException} → 422 (lineage / source-table mismatch
 *       caught by the V19 trigger)</li>
 * </ul>
 */
public class AccessScopeException extends RuntimeException {

    private final String errorCode;

    public AccessScopeException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AccessScopeException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public static class ScopeNotFoundException extends AccessScopeException {
        private final Long scopeId;

        public ScopeNotFoundException(Long scopeId) {
            super("ScopeNotFound", "Scope not found: id=" + scopeId);
            this.scopeId = scopeId;
        }

        public Long getScopeId() {
            return scopeId;
        }
    }

    public static class ScopeAlreadyRevokedException extends AccessScopeException {
        private final Long scopeId;
        private final Instant revokedAt;

        public ScopeAlreadyRevokedException(Long scopeId, Instant revokedAt) {
            super("ScopeAlreadyRevoked",
                    "Scope " + scopeId + " is already revoked at " + revokedAt);
            this.scopeId = scopeId;
            this.revokedAt = revokedAt;
        }

        public Long getScopeId() {
            return scopeId;
        }

        public Instant getRevokedAt() {
            return revokedAt;
        }
    }

    public static class ScopeAlreadyGrantedException extends AccessScopeException {
        public ScopeAlreadyGrantedException(String message, Throwable cause) {
            super("ScopeAlreadyGranted", message, cause);
        }
    }

    public static class ScopeValidationException extends AccessScopeException {
        public ScopeValidationException(String message, Throwable cause) {
            super("ScopeValidation", message, cause);
        }
    }
}
