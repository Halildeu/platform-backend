package com.example.user.authz;

import com.example.commonauth.AuthorizationContext;
import com.example.commonauth.AuthorizationContextCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.stereotype.Service;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AuthorizationContextService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationContextService.class);

    private final WebClient webClient;
    private final AuthorizationContextCache cache;

    public AuthorizationContextService(@Qualifier("plainWebClientBuilder") WebClient.Builder webClientBuilder,
                                       AuthorizationContextCache cache,
                                       @Value("${permission.service.base-url:http://permission-service}") String baseUrl) {
        this.webClient = webClientBuilder == null ? null : webClientBuilder.baseUrl(baseUrl).build();
        this.cache = cache;
    }

    public AuthorizationContext buildContext(Jwt jwt, List<GrantedAuthority> authorities) {
        if (jwt == null) {
            return AuthorizationContext.of(null, null, Collections.emptySet(), Collections.emptySet());
        }
        if (webClient == null) {
            // No upstream ⇒ no revision to check ⇒ nothing may be cached. Deriving from the JWT is
            // already a degraded path; holding onto it would turn it into a stale grant.
            return loadContext(jwt, authorities);
        }
        // board #2556: reuse is bound to the authorization revision, not to elapsed time. The old
        // key (subject:exp:tokenHash) survived a revoke for the whole TTL, and isReusable() cached
        // exactly the decisions that grant something — the ones that must not go stale.
        String cacheKey = buildCacheKey(jwt);
        String token = jwt.getTokenValue();
        return cache.get(cacheKey, () -> fetchAuthzVersion(token, jwt), () -> loadContext(jwt, authorities));
    }

    public AuthorizationContext getCurrentUserContext() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            return AuthorizationContext.of(null, null, Collections.emptySet(), Collections.emptySet());
        }
        Jwt jwt = authentication.getPrincipal() instanceof Jwt j ? j : null;
        List<GrantedAuthority> authorities = authentication.getAuthorities() == null
                ? Collections.emptyList()
                : new ArrayList<>(authentication.getAuthorities());
        return buildContext(jwt, authorities);
    }

    /**
     * Derives authority from permission-service — and only from permission-service (board #2556).
     *
     * <p><b>The JWT fallback is gone on purpose.</b> This method used to catch any failure and
     * rebuild authority from the token's {@code permissions}/authorities claims. That made the
     * token its own authority: a claim minted before a revoke still granted access, and the
     * revision cannot save us here — the revision versions FGA state, it does not certify that a
     * claim is current. Concretely: revoke lands, the revision bumps, {@code /authz/me} then blips,
     * and the stale claim-derived context gets cached *under the new revision* — the exact incident
     * this class exists to prevent, wearing a fresh label.
     *
     * <p>So an unreachable permission-service now fails closed (503) instead of degrading into
     * "trust the token". That is a deliberate availability-for-security trade: the JWT still proves
     * *who* the caller is; it never says what they may do.
     */
    private AuthorizationContext loadContext(Jwt jwt, List<GrantedAuthority> authorities) {
        String token = jwt.getTokenValue();
        AuthzMeResponse body;
        try {
            body = webClient.get()
                    .uri("/api/v1/authz/me")
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .bodyToMono(AuthzMeResponse.class)
                    .block();
        } catch (Exception ex) {
            log.warn("permission-service /authz/me unreachable: {} — failing closed (no JWT authority fallback)",
                    ex.getMessage());
            throw new AuthorizationContextCache.RevisionUnavailableException(
                    "permission-service /authz/me unavailable; refusing to derive authority from the token", ex);
        }
        if (body == null) {
            throw new AuthorizationContextCache.RevisionUnavailableException(
                    "permission-service /authz/me returned no body; refusing to guess authority", null);
        }

        Set<String> permissions = body.permissions() != null
                ? expandPermissionAliases(Set.copyOf(body.permissions()))
                : Collections.emptySet();

        Set<Long> allowedCompanies = body.allowedScopes() != null
                ? body.allowedScopes().stream()
                .filter(s -> "COMPANY".equalsIgnoreCase(s.scopeType()))
                .map(ScopeSummaryDto::scopeRefId)
                .filter(id -> id != null)
                .collect(Collectors.toSet())
                : Collections.emptySet();

        Long userId = tryParseLong(body.userId());
        String email = firstNonBlank(jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username"));
        // Roles still come from the granted authorities (populated by our own JWT converter); they
        // are not an authority source on their own — every protected surface checks permissions.
        Set<String> roles = extractRoles(authorities);

        return AuthorizationContext.of(userId, email, roles, permissions, allowedCompanies,
                Collections.emptySet(), Collections.emptySet());
    }

    private static Set<String> extractPermissionsFromJwt(Jwt jwt, List<GrantedAuthority> authorities) {
        Set<String> claimPerms = jwt.getClaimAsStringList("permissions") != null
                ? Set.copyOf(jwt.getClaimAsStringList("permissions"))
                : Collections.emptySet();
        Set<String> raw = !claimPerms.isEmpty()
                ? claimPerms
                : (authorities == null ? Collections.emptySet() :
                authorities.stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toSet()));
        return expandPermissionAliases(raw);
    }

    private static Set<String> expandPermissionAliases(Set<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return Collections.emptySet();
        }
        HashSet<String> expanded = new HashSet<>(permissions);

        if (permissions.contains("VIEW_USERS")) {
            expanded.add("user-read");
            expanded.add("user-export");
        }

        if (permissions.contains("VIEW_REPORTS")) {
            expanded.add("user-read");
        }

        if (permissions.contains("MANAGE_USERS")) {
            expanded.add("user-read");
            expanded.add("user-create");
            expanded.add("user-update");
            expanded.add("user-delete");
            expanded.add("user-export");
            expanded.add("user-import");
        }

        return expanded;
    }

    private static Set<String> extractRoles(List<GrantedAuthority> authorities) {
        return authorities == null ? Collections.emptySet() :
                authorities.stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toSet());
    }

    private static Long tryParseLong(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /**
     * board #2556: identifies the principal, not the token.
     *
     * <p>The previous key mixed in the token's {@code exp} and a hash of its value, so a refreshed
     * token silently started a new entry (looking "fresh" without being re-authorized) while an
     * unchanged token kept a revoked grant for the whole TTL. Authority belongs to the principal, so
     * the entry does too; whether it is still valid is now decided by the revision.
     */
    private static String buildCacheKey(Jwt jwt) {
        String subject = firstNonBlank(jwt.getSubject(), jwt.getClaimAsString("preferred_username"), "anonymous");
        return AuthorizationContextCache.key(jwt.getClaimAsString("iss"), subject, null);
    }

    /**
     * Reads permission-service's authorization revision — the cheap counter bumped after every FGA
     * mutation. Failure propagates deliberately: the cache turns it into "refuse to reuse a cached
     * grant" (503) instead of treating unknown as unchanged, which is exactly how a revoked grant
     * used to survive.
     *
     * <p><b>The call carries the caller's own bearer token, exactly like {@code /authz/me} above.</b>
     * permission-service authenticates every {@code /api/v1/**} route, so a bare request is answered
     * 401 — and that 401 surfaced two layers up as a 500 on {@code /api/v1/users}, leaving user
     * listing and creation unusable cell-wide while both pods reported Ready.
     *
     * <p>An earlier attempt sent {@code X-Internal-Api-Key} instead. That header is read by
     * permission-service's {@code InternalApiKeyAuthFilter}, which is {@code @Profile({"local","dev"})},
     * matches only {@code /api/v1/internal/**}, and short-circuits entirely unless the legacy flag is
     * on — so on this path nothing reads it. Measured against the running cell: bare → 401,
     * {@code X-Internal-Api-Key} → 401, caller's bearer token → 200. The revision is a single global
     * counter, so relaying the credential the request already carries widens no surface.
     *
     * <p><b>Status mapping (2026-07-27).</b> The 503 promised above was documented but never
     * implemented — no branch existed, so every failure on this path fell through to the generic
     * handler as 500, and an expired session rendered as "could not retrieve user data, check your
     * connection": a recoverable state shown as a dead end.
     *
     * <p>Only an <b>actually expired</b> caller token yields <b>401</b>. A rejection from
     * permission-service against a still-valid token yields <b>503</b>, because that rejection is
     * ambiguous and variant-service already paid for the naive reading (iter-42): the frontend's
     * shared-http listener turns any 401 into a global session expiry, so one dependency blip would
     * log every user out. Neither branch relaxes fail-closed — an unknown revision is never read as
     * unchanged.
     */
    /** Clock skew allowance mirrors what a resource server would tolerate on the same token. */
    private static boolean isExpired(Jwt jwt) {
        Instant exp = jwt == null ? null : jwt.getExpiresAt();
        return exp != null && exp.isBefore(Instant.now().minusSeconds(30));
    }

    private long fetchAuthzVersion(String token, Jwt jwt) {
        Map<?, ?> body;
        try {
            body = webClient.get()
                    .uri("/api/v1/authz/version")
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (WebClientResponseException.Unauthorized | WebClientResponseException.Forbidden ex) {
            // A rejection here is ambiguous: the relayed credential is the caller's own, but
            // permission-service can also reject a perfectly good token for its own reasons — key
            // rotation, clock skew, a misconfigured issuer. So the rejection alone is NOT evidence
            // about the session, and answering 401 on it would be actively harmful: variant-service
            // already learned (iter-42) that the frontend's shared-http listener turns any 401 into
            // a GLOBAL session expiry, so one permission-service blip logs everybody out.
            //
            // The token itself settles it. If it has actually expired, 401 is honest and the client
            // recovers by refreshing. If it is still valid, this is the dependency's problem and
            // must not cost the user their session.
            if (isExpired(jwt)) {
                log.warn("authz revision: caller token is expired ({}) — 401 so the session is revalidated",
                        ex.getStatusCode());
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "ERR_SESSION_REVALIDATION_REQUIRED", ex);
            }
            log.warn("authz revision: permission-service rejected a still-valid caller token ({}) — "
                    + "treating as a dependency failure, NOT a session expiry", ex.getStatusCode());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ERR_AUTHZ_REVISION_UNAVAILABLE", ex);
        } catch (RuntimeException ex) {
            // Anything else — unreachable, 5xx, timeout — is the platform's problem, not the
            // session's. Fail CLOSED, exactly as documented above: never let an unknown revision be
            // read as "unchanged", because that is how a revoked grant used to survive its TTL.
            log.warn("authz revision unavailable, refusing to reuse a cached grant: {}", ex.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ERR_AUTHZ_REVISION_UNAVAILABLE", ex);
        }
        Object value = body == null ? null : body.get("authzVersion");
        if (value instanceof Number number) {
            return number.longValue();
        }
        // A 200 whose body carries no usable revision is still an unknown revision.
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ERR_AUTHZ_REVISION_UNAVAILABLE", null);
    }

}
