package com.example.permission.security;

import com.example.commonauth.identity.ResolvedUserIdentity;
import com.example.commonauth.identity.UserIdentityDirectory;
import com.example.commonauth.identity.UserIdentityDirectoryUnavailableException;
import com.example.permission.service.AuthenticatedUserLookupService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * permission-service adapter to the canonical identity port (board #2532, wire step, umbrella #2530).
 *
 * <p><b>Why this is a wrap rather than a direct HTTP call.</b> The foundation slice (#851) added
 * user-service {@code POST /api/users/internal/authenticated-principal/resolve}, but that endpoint
 * sits behind the {@code /internal/**} chain and demands the {@code PERM_users:internal} authority,
 * which requires a service-token minted by auth-service. permission-service does not yet mint
 * service tokens; adding that plumbing here would blow the wire step's scope. Wrapping the existing
 * {@link AuthenticatedUserLookupService} — which already reaches user-service via the auth-free
 * {@code /api/users/by-email/{email}} path — lands the resolver semantics today without dragging
 * the service-token flow onto the critical path. A follow-up PR migrates the transport to the
 * canonical endpoint and adds subject binding back-fill.
 *
 * <p><b>What this delivers today.</b> The controller now speaks {@link ResolvedUserIdentity} and
 * decides responses from {@link com.example.commonauth.identity.AuthenticatedPrincipalResolver}'s
 * {@code Outcome}. Directory outages surface as {@link UserIdentityDirectoryUnavailableException}
 * (→ 503) rather than silently becoming a deny — the same defect the foundation slice removes.
 *
 * <p><b>Fields we cannot verify from the wrapped path.</b> The lookup service returns only the
 * numeric id (via SQL or {@code /by-email}). Subject binding, activation/deletion state and
 * companyId are not on the wire, so:
 * <ul>
 *   <li>{@code subjectMatched} is always {@code false} — this path is by construction the email
 *       fallback ({@code kc_subject} was never consulted here).</li>
 *   <li>{@code enabled=true, deleted=false} — the previous code path never rejected on these
 *       either, so we do not tighten silently. The follow-up (canonical endpoint) enforces these
 *       activation gates before OpenFGA is consulted, which is the whole point of the resolver.</li>
 *   <li>{@code companyId=null} — not needed by {@code /authz/me}.</li>
 * </ul>
 * These conservative defaults preserve the existing 200-body shape while the enforcement gates
 * ride along the follow-up (foundation PR body notes the same staged rollout).
 */
public class AuthenticatedUserLookupIdentityDirectory implements UserIdentityDirectory {

    private static final Logger log =
            LoggerFactory.getLogger(AuthenticatedUserLookupIdentityDirectory.class);

    private final AuthenticatedUserLookupService lookupService;

    public AuthenticatedUserLookupIdentityDirectory(AuthenticatedUserLookupService lookupService) {
        this.lookupService = lookupService;
    }

    @Override
    public Optional<ResolvedUserIdentity> resolve(String issuer, String subject, String email) {
        Jwt jwt = synthesizeJwt(issuer, subject, email);
        AuthenticatedUserLookupService.ResolvedAuthenticatedUser resolved;
        try {
            resolved = lookupService.resolve(jwt);
        } catch (RuntimeException ex) {
            // Match the endpoint-admin adapter's semantic: a transport / DB failure is an outage,
            // NOT "no such user". Falling back to the raw subject is exactly the defect removed.
            log.warn("permission-service identity lookup failed: {}", ex.getMessage());
            throw new UserIdentityDirectoryUnavailableException(
                    "AuthenticatedUserLookupService.resolve threw", ex);
        }

        if (resolved == null || resolved.numericUserId() == null) {
            // No numeric id → no canonical row. The lookup service itself returns null on "not
            // found"; it cannot distinguish that from a DB blip, so we accept its answer here.
            return Optional.empty();
        }

        // See class javadoc for why these defaults are safe: the wrapped path never enforced
        // activation/deletion gates either, so we do not tighten silently. Enforcement moves to
        // the canonical HTTP endpoint in the follow-up PR.
        return Optional.of(new ResolvedUserIdentity(
                resolved.numericUserId(),
                subject,
                resolved.email(),
                /*subjectMatched=*/ false,
                /*enabled=*/ true,
                /*deleted=*/ false,
                /*companyId=*/ null));
    }

    /**
     * Wrap the already-verified binding claims back into a {@link Jwt} the wrapped lookup service
     * can consume — it needs a token to reach email and subject. This is a lossless re-hydration:
     * every field we pass here was extracted from a token the resource server already validated.
     */
    private static Jwt synthesizeJwt(String issuer, String subject, String email) {
        java.util.Map<String, Object> claims = new java.util.HashMap<>();
        if (subject != null) {
            claims.put("sub", subject);
        }
        if (email != null) {
            claims.put("email", email);
        }
        java.util.Map<String, Object> headers = java.util.Map.of("alg", "none");
        java.time.Instant now = java.time.Instant.now();
        try {
            Jwt.Builder builder = Jwt.withTokenValue("adapter-synthetic")
                    .headers(h -> h.putAll(headers))
                    .claims(c -> c.putAll(claims))
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(60));
            if (subject != null) {
                builder = builder.subject(subject);
            }
            if (issuer != null) {
                builder = builder.issuer(issuer);
            }
            return builder.build();
        } catch (JwtException ex) {
            // A JwtException here would mean our own construction is broken — treat as unavailable
            // rather than smuggling a partially-built token into the lookup service.
            throw new UserIdentityDirectoryUnavailableException(
                    "unable to synthesize resolution token", ex);
        }
    }
}
