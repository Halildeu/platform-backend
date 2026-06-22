package com.example.user.service;

import com.example.user.model.User;
import com.example.user.repository.UserRepository;
import com.example.user.security.JwtAutoProvisionGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * Single source of truth for resolving the backend {@link User} profile
 * of the currently authenticated principal.
 *
 * <p>Before this component the resolution logic was copy-pasted into
 * {@code UserControllerV1}, the legacy {@code UserController} and
 * {@code NotificationPreferencesControllerV1} — three drifting copies of
 * the same {@code requireCurrentUser()} method. They now all delegate
 * here.
 *
 * <p>This resolver is also the <em>safety net</em> for the Keycloak user
 * lazy-provision bridge. The main hook is
 * {@link com.example.user.security.KeycloakUserAutoProvisionFilter},
 * which provisions the profile before the request reaches a controller.
 * If that filter did not run (e.g. a path it excludes, or future wiring
 * changes), this resolver re-applies the exact same
 * {@link JwtAutoProvisionGate} so an M365 first-login still resolves
 * instead of falling through to {@code 403 PROFILE_MISSING}.
 *
 * <h2>403 {@code PROFILE_MISSING} — narrowed, not removed</h2>
 * The resolver still throws {@code 403 PROFILE_MISSING} (fail-closed) when:
 * <ul>
 *   <li>a numeric {@code userId} claim is present but no longer maps to a
 *       row — a stale token / deleted profile, NOT a first login;</li>
 *   <li>the JWT is outside the auto-provision gate (issuer/tenant not
 *       allowed, or missing the M365 marker) and no profile exists;</li>
 *   <li>the JWT is missing an email or {@code sub} and no profile exists.</li>
 * </ul>
 *
 * <h2>403 {@code ACCOUNT_DISABLED} — activation gate</h2>
 * Once a profile is resolved, a passive one ({@code enabled=false}) is
 * rejected with {@code 403 ACCOUNT_DISABLED}. An M365 first-login is
 * auto-provisioned <em>passive</em> (the admin-manually-authorizes model),
 * so its first request provisions the row then fails this gate — an admin
 * flips it active via {@code updateActivation} ("Kullanıcı Yönetimi").
 * Local username/password login is already gated by Spring Security
 * ({@code UserDetails.isEnabled()}); JWT/session auth bypasses that, so the
 * check is re-applied here, the single current-user resolution point.
 */
@Component
public class CurrentUserResolver {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserResolver.class);

    private final UserRepository userRepository;
    private final UserService userService;
    private final JwtAutoProvisionGate autoProvisionGate;

    public CurrentUserResolver(UserRepository userRepository,
                               UserService userService,
                               JwtAutoProvisionGate autoProvisionGate) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.autoProvisionGate = autoProvisionGate;
    }

    /**
     * Resolves the {@link User} for the current {@link SecurityContextHolder}
     * authentication, lazily provisioning an M365 first-login profile when
     * the auto-provision gate permits, then enforcing the account-activation
     * gate — a passive ({@code enabled=false}) profile cannot transact.
     *
     * @return the resolved, <em>active</em> backend {@link User}
     * @throws ResponseStatusException {@code 401} when unauthenticated,
     *         {@code 403 PROFILE_MISSING} per the narrowed rules above,
     *         {@code 403 ACCOUNT_DISABLED} when the resolved profile is
     *         passive ({@code enabled=false})
     */
    public User resolveCurrentUser() {
        User user = resolveAuthenticatedUser();
        // Soft-delete tombstone gate (Codex 019ea573, #770 Phase 2). A
        // soft-deleted profile must never transact, regardless of principal
        // type (User / numeric-userId JWT / lazy-provision bridge /
        // gate-denied JWT / service token). Because the entity carries NO
        // global @Where, every resolution path returns the tombstone row and
        // this single choke point converts it to a clean 403 USER_DELETED.
        // Checked BEFORE the activation gate so a deleted-and-disabled row
        // reports the more specific terminal state.
        if (user.isDeleted()) {
            log.warn("Silinmiş hesap isteği reddedildi (deleted_at set): userId={} email={}",
                    user.getId(), user.getEmail());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "USER_DELETED");
        }
        // Account-activation gate. A passive (enabled=false) profile — an
        // M365 first-login auto-provisioned account awaiting admin
        // activation, or a manually-disabled account — must not transact
        // via a JWT/session request. Local username/password login is
        // already gated by Spring Security (UserDetails.isEnabled() →
        // DisabledException); JWT auth bypasses that, so the check is
        // re-applied here, the single current-user resolution point.
        if (!user.isEnabled()) {
            log.warn("Pasif hesap isteği reddedildi (enabled=false): userId={} email={}",
                    user.getId(), user.getEmail());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
        }
        return user;
    }

    /**
     * Resolves the backend {@link User} of the current principal WITHOUT
     * the activation gate — principal-type branching only. Private; the
     * gated public entry point is {@link #resolveCurrentUser()}.
     */
    private User resolveAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kimlik doğrulaması gerekli");
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof User user) {
            return user;
        }

        if (principal instanceof Jwt jwt) {
            return resolveFromJwt(jwt, authentication);
        }

        // Non-JWT, non-User principal (e.g. service token / local api-key
        // UserDetails) — resolve by name only, never auto-provision.
        String username = authentication.getName();
        if (!StringUtils.hasText(username)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kimlik doğrulaması gerekli");
        }
        return userRepository.findByEmailIgnoreCase(username)
                .orElseThrow(() -> {
                    log.warn("Kimliği doğrulanan kullanıcı için yerel profil yok: {}", username);
                    return new ResponseStatusException(HttpStatus.FORBIDDEN, "PROFILE_MISSING");
                });
    }

    private User resolveFromJwt(Jwt jwt, Authentication authentication) {
        String subject = blankToNull(jwt.getSubject());
        String email = firstNonBlank(
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("preferred_username"),
                authentication.getName());
        String canonicalEmail = UserService.canonicalEmail(email);

        // 1. Gate-passing JWT (M365 bridge identity) — `sub`/email is the
        //    authority. The gate certifies the JWT `sub` is a trustworthy
        //    Keycloak subject; only then may UserService#lazyProvisionFromJwt
        //    link it (kcSubject hit → return / email-match → backfill or
        //    403 IDENTITY_LINK_CONFLICT / no match → insert passive).
        //
        //    Evaluated FIRST, ahead of any numeric `userId` claim (changed
        //    2026-06-22, Codex 019eeffd): an M365 Keycloak `userId` attribute
        //    can be ORPHANED — a stale id with no backend row, left from an
        //    earlier provisioning before a dev-DB reset. Keying on it would
        //    either lock the user out (old 403 PROFILE_MISSING) or, worse,
        //    resolve a row belonging to a DIFFERENT identity. So an M365
        //    first-login — even one carrying a stale userId claim —
        //    provisions correctly here.
        //
        //    lazyProvisionFromJwt may throw 403 IDENTITY_LINK_CONFLICT; that
        //    is intentional fail-closed and must NOT be downgraded to
        //    PROFILE_MISSING.
        JwtAutoProvisionGate.Decision decision = autoProvisionGate.evaluate(jwt);
        if (decision.allowed()) {
            log.debug("CurrentUserResolver resolving M365 identity via lazy-provision bridge sub={} email={}",
                    decision.command().kcSubject(), decision.command().email());
            return userService.lazyProvisionFromJwt(decision.command());
        }

        // 2. Gate-denied JWT (e.g. a local/auth-service token whose
        //    backend-issued `userId` claim is authoritative). Honor the
        //    numeric userId ONLY when the resolved row actually belongs to
        //    this token identity (kcSubject == sub, or canonical email
        //    match) — a stale/reused id that maps to a DIFFERENT row, or to
        //    none, must never resolve as the current user (anti-cross-user).
        //    On mismatch/absence, ignore the claim and fall through to the
        //    plain identity lookup; the resolved row's own deleted/disabled
        //    state is still enforced by resolveCurrentUser(). (Codex 019eeffd.)
        Long numericUserId = extractNumericUserId(jwt);
        if (numericUserId != null) {
            Optional<User> byId = userRepository.findById(numericUserId);
            if (byId.isPresent() && rowMatchesTokenIdentity(byId.get(), subject, canonicalEmail)) {
                return byId.get();
            }
            log.warn("stale-user-id-claim-mismatch: userId claim {} did not resolve to a row matching "
                    + "the token identity; ignoring claim", numericUserId);
        }

        // 3. Plain non-mutating profile lookup (kcSubject then canonical
        //    email): no backfill, no identity-link conflict, no version bump
        //    — `sub` here is not a certified Keycloak subject. Fail closed
        //    when none exists.
        Optional<User> existing = findExistingWithoutProvision(subject, canonicalEmail);
        if (existing.isPresent()) {
            return existing.get();
        }

        log.warn("Keycloak kullanıcısı için yerel profil yok ve auto-provision reddedildi (reason={}): email={}",
                decision.denyReason(), canonicalEmail);
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "PROFILE_MISSING");
    }

    /**
     * True when the resolved row actually belongs to the token's identity —
     * its {@code kcSubject} equals the JWT {@code sub}, or its canonical
     * email matches the token's. Guards the numeric {@code userId} claim
     * (gate-denied path) against a stale/reused id that maps to a DIFFERENT
     * user's row: without this an attacker-or-stale token could resolve
     * another user as the current user (cross-user). (Codex 019eeffd.)
     */
    private static boolean rowMatchesTokenIdentity(User row, String subject, String canonicalEmail) {
        if (StringUtils.hasText(subject)
                && subject.equals(blankToNull(row.getKcSubject()))) {
            return true;
        }
        if (StringUtils.hasText(canonicalEmail)) {
            return canonicalEmail.equalsIgnoreCase(UserService.canonicalEmail(row.getEmail()));
        }
        return false;
    }

    /**
     * Plain non-mutating profile lookup for a gate-denied JWT — matches
     * by {@code kcSubject} then case-insensitive email and returns the
     * row as-is. Deliberately does NOT backfill {@code kcSubject} or
     * apply the identity-link-conflict rule: a gate-denied JWT's
     * {@code sub} is not a certified Keycloak subject (local/service
     * issuers put the email there), so writing it onto a profile — and
     * bumping the optimistic-locking {@code @Version} — would be wrong.
     */
    private Optional<User> findExistingWithoutProvision(String subject, String canonicalEmail) {
        if (StringUtils.hasText(subject)) {
            Optional<User> bySubject = userRepository.findByKcSubject(subject);
            if (bySubject.isPresent()) {
                return bySubject;
            }
        }
        if (StringUtils.hasText(canonicalEmail)) {
            return userRepository.findByEmailIgnoreCase(canonicalEmail);
        }
        return Optional.empty();
    }

    private static Long extractNumericUserId(Jwt jwt) {
        Object rawUserId = jwt.getClaim("userId");
        if (rawUserId instanceof Number num) {
            return num.longValue();
        }
        if (rawUserId instanceof String str) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = blankToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
