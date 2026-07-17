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
import org.springframework.web.reactive.function.client.WebClient;

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
        return cache.get(cacheKey, this::fetchAuthzVersion, () -> loadContext(jwt, authorities));
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
     */
    private long fetchAuthzVersion() {
        Map<?, ?> body = webClient.get()
                .uri("/api/v1/authz/version")
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        Object value = body == null ? null : body.get("authzVersion");
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("permission-service /authz/version returned no usable authzVersion");
    }

}
