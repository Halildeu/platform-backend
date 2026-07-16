package com.example.commonauth.identity;

/**
 * Canonical identity of an authenticated principal (board #2532).
 *
 * <p>{@code userId} is the platform numeric id — the ONLY form that may be used as an OpenFGA
 * {@code user:} subject. Keycloak UUIDs and emails must never become subjects: tuples are written
 * for the numeric id, so asking about a UUID silently answers "no" and denies a legitimate admin.
 *
 * @param userId        platform numeric user id (OpenFGA subject)
 * @param subject       Keycloak {@code sub} bound to this row ({@code kc_subject}), may be null for
 *                      legacy rows resolved by email
 * @param email         canonical email
 * @param subjectMatched whether the token {@code sub} matched the stored binding (false ⇒ resolved
 *                      via the controlled email fallback)
 * @param enabled       activation state; a disabled account must not consume protected surfaces
 * @param deleted       soft-delete state
 * @param companyId     owning company, may be null (NOT a tenant UUID — see JwtTenantContextResolver)
 */
public record ResolvedUserIdentity(
        long userId,
        String subject,
        String email,
        boolean subjectMatched,
        boolean enabled,
        boolean deleted,
        Long companyId) {

    /** @return true when this principal may proceed past the activation/deletion gate. */
    public boolean usable() {
        return enabled && !deleted;
    }
}
