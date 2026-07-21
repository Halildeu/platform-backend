package com.example.user.controller;

import com.example.user.model.User;
import com.example.user.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Narrow identity-resolution surface for trusted services (board #2532, umbrella #2530).
 *
 * <p><b>Why a new endpoint.</b> Services that must turn a validated JWT into the canonical platform
 * principal previously had no safe way to do it:
 * <ul>
 *   <li>{@code GET /api/users/internal/by-email/{email}} exists but returns the password hash, the
 *       role catalogue and session settings — far more than an authorization decision needs, and it
 *       was never designed for subject binding. Handing that to every service that needs a numeric
 *       id is an unnecessary credential-exposure surface.</li>
 *   <li>Without it, endpoint-admin fell back to {@code jwt.getSubject()} (a Keycloak UUID) as the
 *       OpenFGA subject while every tuple is written for the numeric id — so an authorized admin
 *       got 403 (board #2532; same class as the OI-03 defect documented in permission-service).</li>
 * </ul>
 *
 * <p><b>Contract.</b> user-service owns the identity row, so it — not the caller — decides how a
 * token maps to a user:
 * <ul>
 *   <li>{@code kc_subject} is the primary binding key; email is only a controlled fallback for rows
 *       created before subject binding existed (and it back-fills the binding on first match).</li>
 *   <li>The response carries exactly what an authorization decision needs: numeric id, whether the
 *       subject matched, activation/deletion state, company and email. No password, no role
 *       catalogue, no session config.</li>
 *   <li>Not found ⇒ {@code 404}. Callers must translate that to a deny — never to "fall back to the
 *       raw sub", which is exactly how the UUID-subject bug stayed invisible.</li>
 * </ul>
 *
 * <p>Protected by the same service-to-service authority as the other internal surfaces
 * ({@code PERM_users:internal}; see {@code SecurityConfig#internalServiceTokenFilterChain}).
 */
@RestController
@RequestMapping("/api/users/internal")
public class AuthenticatedPrincipalInternalController {

    private static final Logger log =
            LoggerFactory.getLogger(AuthenticatedPrincipalInternalController.class);

    private final UserRepository userRepository;

    /**
     * Board #2582 — count identities resolved with NO owning company.
     *
     * <p>Measured on testai 2026-07-17: 10 of 12 rows in {@code users_db.users} carry a null
     * {@code company_id}. Those identities do not state which organisation they belong to, so the
     * plane substitutes {@code companyId=1} and derives the org
     * {@code UUID.nameUUIDFromBytes("company:1")}. The admin account is one of them: it resolves to
     * an org it never declared. Today that is harmless because there is one tenant; in a
     * multi-tenant deployment it is a user silently landing in the WRONG org.
     *
     * <p>The substitution happens with no error and no log line anywhere — which is why it survived
     * long enough to become the root of the #2559 empty-fleet symptom. This counter makes the
     * population visible without changing a single authorization outcome.
     *
     * <p>Deliberately a counter on the resolve path rather than a gauge over the table: it measures
     * org-less identities that are actually BEING USED, which is the number that matters for the
     * migration. A row nobody authenticates as is not urgent.
     */
    @Nullable
    private final Counter orglessPrincipalCounter;

    @Autowired
    public AuthenticatedPrincipalInternalController(UserRepository userRepository,
                                                    @Nullable MeterRegistry meterRegistry) {
        this.userRepository = userRepository;
        this.orglessPrincipalCounter = meterRegistry == null ? null
                : Counter.builder("identity_principal_without_company_total")
                        .description("Principals resolved with a null company_id — the identity does "
                                + "not state its own organisation and the plane substitutes the "
                                + "default (board #2582; target: sustained zero)")
                        .register(meterRegistry);
    }

    /** Test-friendly constructor; no metrics. */
    public AuthenticatedPrincipalInternalController(UserRepository userRepository) {
        this(userRepository, null);
    }

    /** Request: the already-validated token's binding claims. */
    public record ResolveRequest(String issuer, String subject, String email) {}

    /**
     * Response: only what an authorization decision needs.
     *
     * @param userId        numeric platform id — the ONLY valid OpenFGA {@code user:} subject
     * @param subjectMatched true when the row was found by {@code kc_subject}; false ⇒ resolved via
     *                       the email fallback (useful for callers that want to tighten later)
     * @param email          canonical email
     * @param enabled        activation state (a disabled account must not consume protected surfaces)
     * @param deleted        soft-delete state
     * @param companyId      owning company — NOT a tenant UUID
     */
    public record ResolveResponse(
            long userId,
            boolean subjectMatched,
            String email,
            boolean enabled,
            boolean deleted,
            Long companyId) {}

    @PostMapping("/authenticated-principal/resolve")
    public ResponseEntity<ResolveResponse> resolve(@RequestBody ResolveRequest request) {
        requireServiceAuthority("PERM_users:internal");

        if (request == null) {
            return ResponseEntity.badRequest().build();
        }
        String subject = trimToNull(request.subject());
        String email = normaliseEmail(request.email());
        if (subject == null && email == null) {
            // Nothing to bind on: this is a caller bug, not "no such user".
            return ResponseEntity.badRequest().build();
        }

        Optional<User> found = Optional.empty();
        boolean subjectMatched = false;
        if (subject != null) {
            found = userRepository.findByKcSubject(subject);
            subjectMatched = found.isPresent();
        }
        if (found.isEmpty() && email != null) {
            // Controlled fallback: rows provisioned before subject binding carry no kc_subject.
            found = userRepository.findByEmailIgnoreCase(email);
            if (found.isPresent() && subject != null && trimToNull(found.get().getKcSubject()) == null) {
                // Back-fill the binding so subsequent resolves take the primary path. Deliberately
                // only when the row has NO binding yet — never overwrite a different subject, which
                // would let one principal steal another's row.
                found.get().setKcSubject(subject);
                userRepository.save(found.get());
                subjectMatched = true;
                log.info("identity resolve: back-filled kc_subject for user id={}", found.get().getId());
            }
        }

        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        User user = found.get();

        // A row bound to a DIFFERENT subject must not be handed to this token, even if the email
        // matches: emails are reassignable, subject bindings are not.
        String bound = trimToNull(user.getKcSubject());
        if (subject != null && bound != null && !bound.equals(subject)) {
            log.warn("identity resolve: email matched user id={} but it is bound to another subject",
                    user.getId());
            return ResponseEntity.notFound().build();
        }

        // #2582: an identity that does not carry its own org is reported, not corrected.
        //
        // Behaviour is UNCHANGED on purpose. Failing closed here would lock out the admin account
        // and 9 other live users immediately, and picking an org for them is an owner decision with
        // irreversible consequences (issue madde 3). What this fixes is the SILENCE: the default
        // substitution downstream now has a name, a count and a log line.
        //
        // Logged at WARN with the user id but WITHOUT the email — the id is enough to find the row,
        // and this line will repeat on every resolve for every affected principal.
        if (user.getCompanyId() == null) {
            if (orglessPrincipalCounter != null) {
                orglessPrincipalCounter.increment();
            }
            log.warn("identity resolve: user id={} has NO company_id — downstream will substitute "
                            + "the default org (UUID.nameUUIDFromBytes(\"company:1\")). The identity "
                            + "does not state its own organisation; in a multi-tenant deployment this "
                            + "is a silent wrong-org assignment. Board #2582; metric "
                            + "identity_principal_without_company_total",
                    user.getId());
        }

        return ResponseEntity.ok(new ResolveResponse(
                user.getId(),
                subjectMatched,
                user.getEmail(),
                user.isEnabled(),
                user.getDeletedAt() != null,
                user.getCompanyId()));
    }

    private static void requireServiceAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedException("service authority required");
        }
        boolean has = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
        if (!has) {
            throw new AccessDeniedException("missing authority: " + authority);
        }
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
