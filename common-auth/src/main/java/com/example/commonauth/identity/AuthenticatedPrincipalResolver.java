package com.example.commonauth.identity;

import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Resolves a validated JWT into the canonical platform principal (board #2532, umbrella #2530).
 *
 * <p><b>The bug this replaces.</b> {@code EndpointAdminRequireModuleInterceptor} did:
 * <pre>
 *   userId claim present ? String.valueOf(claim) : jwt.getSubject()   // ← Keycloak UUID
 * </pre>
 * and handed that straight to OpenFGA as the {@code user:} subject. Tuples are written for the
 * numeric platform id, so a browser token (sub + email, no {@code userId} claim) always checked a
 * UUID that owns no tuples → a legitimately authorized admin received 403. Meanwhile
 * permission-service resolved email→numeric properly: same request, two different principals.
 *
 * <p><b>Rules (fail-closed):</b>
 * <ul>
 *   <li>The {@code userId}/{@code uid} claim is a <em>hint to verify</em>, never an authority. If it
 *       disagrees with the canonical row, the request is rejected — an attacker-supplied or stale
 *       claim must not select someone else's identity.</li>
 *   <li>The Keycloak {@code sub} is a binding key, never an OpenFGA subject.</li>
 *   <li>Email is never an OpenFGA subject either; it is only a controlled lookup fallback for rows
 *       that predate subject binding.</li>
 *   <li>No canonical row ⇒ {@link Outcome#PROFILE_MISSING}. There is no "fall back to the raw sub"
 *       path: that is precisely how the UUID-subject defect stayed invisible.</li>
 *   <li>Directory unavailable ⇒ {@link Outcome#DIRECTORY_UNAVAILABLE} (caller: 503). An outage is
 *       not an authorization answer.</li>
 *   <li>Disabled/deleted ⇒ denied here, <em>before</em> any OpenFGA call.</li>
 * </ul>
 *
 * <p>Stateless and free of transport concerns: the directory port decides where the row lives.
 */
public class AuthenticatedPrincipalResolver {

    private static final Logger log = LoggerFactory.getLogger(AuthenticatedPrincipalResolver.class);

    /** Why a resolution failed — callers map these to distinct, non-leaking HTTP responses. */
    public enum Outcome {
        RESOLVED,
        /** Token carried no usable subject/email — cannot even attempt a lookup. */
        UNAUTHENTICATED,
        /** Authenticated, but no canonical user row exists (not provisioned / never activated). */
        PROFILE_MISSING,
        /** Row exists but is disabled — activation gate. */
        ACCOUNT_DISABLED,
        /** Row exists but is soft-deleted. */
        USER_DELETED,
        /** The token's userId/uid claim contradicts the canonical row. */
        IDENTITY_MISMATCH,
        /** The identity authority could not be consulted (timeout/5xx). */
        DIRECTORY_UNAVAILABLE
    }

    /** Resolution result: exactly one of {@code identity} (RESOLVED) or a failure {@code outcome}. */
    public record Resolution(Outcome outcome, ResolvedUserIdentity identity) {
        public boolean resolved() {
            return outcome == Outcome.RESOLVED && identity != null;
        }

        static Resolution fail(Outcome outcome) {
            return new Resolution(outcome, null);
        }

        static Resolution ok(ResolvedUserIdentity id) {
            return new Resolution(Outcome.RESOLVED, id);
        }
    }

    private final UserIdentityDirectory directory;

    public AuthenticatedPrincipalResolver(UserIdentityDirectory directory) {
        this.directory = directory;
    }

    public Resolution resolve(Jwt jwt) {
        if (jwt == null) {
            return Resolution.fail(Outcome.UNAUTHENTICATED);
        }
        String subject = trimToNull(jwt.getSubject());
        String email = normaliseEmail(jwt.getClaimAsString("email"));
        if (subject == null && email == null) {
            log.debug("principal resolve: token carries neither sub nor email");
            return Resolution.fail(Outcome.UNAUTHENTICATED);
        }

        Optional<ResolvedUserIdentity> found;
        try {
            found = directory.resolve(jwt.getIssuer() == null ? null : jwt.getIssuer().toString(),
                    subject, email);
        } catch (UserIdentityDirectoryUnavailableException e) {
            // Deliberately NOT a deny and NOT a claim fallback: an outage must surface as 503.
            log.warn("principal resolve: identity directory unavailable: {}", e.getMessage());
            return Resolution.fail(Outcome.DIRECTORY_UNAVAILABLE);
        }

        if (found.isEmpty()) {
            return Resolution.fail(Outcome.PROFILE_MISSING);
        }
        ResolvedUserIdentity id = found.get();

        // The claim is a hint we verify — never the authority.
        Long claimed = numericClaim(jwt);
        if (claimed != null && claimed != id.userId()) {
            log.warn("principal resolve: userId claim contradicts canonical row (claim={} canonical={})",
                    claimed, id.userId());
            return Resolution.fail(Outcome.IDENTITY_MISMATCH);
        }

        if (id.deleted()) {
            return Resolution.fail(Outcome.USER_DELETED);
        }
        if (!id.enabled()) {
            // Activation gate is enforced BEFORE any OpenFGA call: a disabled account must not be
            // able to consume protected surfaces even if grants were issued to it.
            return Resolution.fail(Outcome.ACCOUNT_DISABLED);
        }
        return Resolution.ok(id);
    }

    /** @return the userId/uid claim as a number, or null when absent/unparsable. */
    private static Long numericClaim(Jwt jwt) {
        for (String name : new String[] {"userId", "uid"}) {
            Object raw = jwt.getClaim(name);
            if (raw == null) {
                continue;
            }
            if (raw instanceof Number n) {
                return n.longValue();
            }
            String s = trimToNull(String.valueOf(raw));
            if (s == null) {
                continue;
            }
            try {
                return Long.valueOf(s);   // frontend parity: the claim ships as a numeric string
            } catch (NumberFormatException e) {
                // A malformed hint is not an identity; ignore it rather than guessing.
                log.debug("principal resolve: ignoring unparsable {} claim", name);
            }
        }
        return null;
    }

    private static String normaliseEmail(String email) {
        String e = trimToNull(email);
        return e == null ? null : e.toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
