package com.example.gpcore.domain;

import java.util.Objects;

/**
 * The caller identity for an authorization decision.
 *
 * <p>Deliberately minimal: it carries only the stable subject id. Subject-side
 * ABAC attributes (clearances, subject-policy version) are NOT embedded here —
 * they are resolved authoritatively via {@code SubjectAttributePort} at decision
 * time so that a caller cannot forge clearances by constructing a Principal, and
 * so that clearance revocation invalidates cached decisions (Codex 019f1913 #3).
 *
 * @param userId stable subject id (OpenFGA {@code user:<userId>})
 */
public record Principal(String userId) {

    public Principal {
        Objects.requireNonNull(userId, "userId");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
    }

    public static Principal of(String userId) {
        return new Principal(userId);
    }
}
