package com.example.user.dto;

/**
 * Internal response shape for the
 * {@code GET /api/users/internal/{id}/impersonation-target} endpoint
 * consumed by auth-service ImpersonationController for backend-authoritative
 * Keycloak subject resolution.
 *
 * <p>Codex thread {@code 019e1bed} REVISE-1 absorb: the public
 * {@code GET /api/v1/users/{id}} endpoint must not expose
 * {@code kc_subject} to browsers; an internal service-token protected
 * endpoint keeps the KC UUID surface server-to-server only.
 */
public record ImpersonationTargetResponse(
        Long id,
        String email,
        String kcSubject,
        boolean enabled
) {
}
