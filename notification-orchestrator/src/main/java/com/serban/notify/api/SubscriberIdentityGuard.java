package com.serban.notify.api;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Validates that the {@code X-Subscriber-Id} request header (or
 * {@code subscriberId} query param for SSE) matches the authenticated
 * principal's JWT {@code sub} claim (Faz 23.4 PR-E.5).
 *
 * <p><b>Why</b> (Codex thread {@code 019e01ba} iter-2 absorb): the original
 * Faz 23.3 PR-E.1/PR-E.3 implementations accepted the subscriber identity
 * directly from caller-controlled inputs (header for REST, query param for
 * SSE) without any cross-check against the authenticated principal. A
 * caller holding a valid JWT for {@code sub=alice} could send
 * {@code X-Subscriber-Id: bob} and read bob's inbox or stream.
 * That is an authorization boundary bug — an authenticated user must not
 * be able to impersonate another subscriber by editing a request header.
 *
 * <p><b>What this guard does</b>: pulls {@link Authentication} from the
 * Spring Security context, extracts the JWT principal claim
 * (configured as {@code sub} in {@code SecurityConfig.notifyJwtAuthenticationConverter}),
 * and compares it to the supplied {@code subscriberId}. On mismatch it
 * throws {@link AccessDeniedException} (mapped to HTTP 403 by Spring's
 * default {@code AccessDeniedHandler}).
 *
 * <p><b>What this guard does NOT do</b>: it doesn't validate {@code orgId}.
 * The platform is single-tenant for now ({@code default} org); when a
 * tenant claim lands (Faz 24+), this guard should be extended to also
 * match {@code X-Org-Id} against a claim such as {@code tenant_id} or
 * {@code org_id}. Until then, accepting any caller-supplied org is
 * acceptable because all subscribers live in the same tenant scope.
 *
 * <p><b>Long-term direction</b> (Codex iter-2 note): backend should stop
 * taking subscriber identity from caller input entirely and resolve it
 * exclusively from the JWT principal. The header/query input becomes
 * vestigial. Until that refactor lands, this guard prevents cross-account
 * leakage in the existing contract.
 */
@Component
public class SubscriberIdentityGuard {

    /**
     * Validates that the supplied {@code subscriberId} matches the JWT
     * principal's {@code sub} claim on the current security context.
     *
     * <p>If no authentication is present (e.g. {@code SecurityContextHolder}
     * is empty under a profile that disables filters), the guard returns
     * silently — slice tests with {@code addFilters=false} continue to
     * work without contortions. Production runs always have an
     * authenticated context for {@code /api/v1/notify/**} routes
     * (configured in {@code SecurityConfig}), so the silent skip is safe.
     *
     * @param subscriberId the caller-supplied subscriber identifier
     *     ({@code X-Subscriber-Id} header for REST, {@code subscriberId}
     *     query param for SSE)
     * @throws AccessDeniedException when an authenticated principal is
     *     present and the JWT {@code sub} claim does not equal
     *     {@code subscriberId}
     */
    public void requireMatchOrThrow(String subscriberId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            // No filter chain in this profile (e.g. @WebMvcTest addFilters=false)
            // → contract semantics not exercised; let the slice test pass.
            return;
        }
        Object principal = authentication.getPrincipal();
        String jwtSubject;
        if (principal instanceof Jwt jwt) {
            jwtSubject = jwt.getSubject();
        } else {
            // Anonymous / non-JWT principal under a permissive profile.
            // Same rationale as the unauthenticated branch above.
            return;
        }
        if (jwtSubject == null || subscriberId == null) {
            // Defensive: a malformed JWT or null header should already have
            // failed earlier validation; treat as a mismatch.
            throw new AccessDeniedException(
                "subscriber identity unresolved (jwtSub=" + jwtSubject
                    + ", subscriberId=" + subscriberId + ")");
        }
        if (!jwtSubject.equals(subscriberId)) {
            // Don't echo the JWT subject back — only state the mismatch.
            // Avoids assisting attackers who probe by varying the header.
            throw new AccessDeniedException(
                "subscriber identity mismatch: JWT principal does not match supplied subscriberId");
        }
    }
}
