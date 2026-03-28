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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AuthorizationContextService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationContextService.class);

    private final WebClient webClient;
    private final AuthorizationContextCache cache;

    public AuthorizationContextService(@Qualifier("loadBalancedWebClientBuilder") WebClient.Builder webClientBuilder,
                                       AuthorizationContextCache cache,
                                       @Value("${permission.service.base-url:http://permission-service}") String baseUrl) {
        this.webClient = webClientBuilder == null ? null : webClientBuilder.baseUrl(baseUrl).build();
        this.cache = cache;
    }

    public AuthorizationContext buildContext(Jwt jwt, List<GrantedAuthority> authorities) {
        if (jwt == null) {
            return AuthorizationContext.of(null, null, Collections.emptySet(), Collections.emptySet());
        }
        String cacheKey = buildCacheKey(jwt);
        AuthorizationContext cached = cache.getIfPresent(cacheKey);
        if (isReusable(cached)) {
            return cached;
        }
        AuthorizationContext fresh = loadContext(jwt, authorities);
        cache.put(cacheKey, fresh);
        return fresh;
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

    private AuthorizationContext loadContext(Jwt jwt, List<GrantedAuthority> authorities) {
        String token = jwt.getTokenValue();

        try {
            AuthzMeResponse body = webClient.get()
                    .uri("/api/v1/authz/me")
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .bodyToMono(AuthzMeResponse.class)
                    .block();
            Set<String> permissions = body != null && body.permissions() != null
                    ? expandPermissionAliases(body.permissions())
                    : extractPermissionsFromJwt(jwt, authorities);

            Set<Long> allowedCompanies = body != null && body.allowedScopes() != null
                    ? body.allowedScopes().stream()
                    .filter(s -> "COMPANY".equalsIgnoreCase(s.scopeType()))
                    .map(ScopeSummaryDto::scopeRefId)
                    .filter(id -> id != null)
                    .collect(Collectors.toSet())
                    : Collections.emptySet();

            Long userId = tryParseLong(body != null ? body.userId() : jwt.getSubject());
            String email = firstNonBlank(jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username"));
            Set<String> roles = extractRoles(authorities);

            return AuthorizationContext.of(userId, email, roles, permissions, allowedCompanies, Collections.emptySet(), Collections.emptySet());
        } catch (Exception ex) {
            log.warn("permission-service /authz/me çağrısı başarısız: {}. JWT izinleriyle devam edilecek.", ex.getMessage());
            Set<String> permissions = extractPermissionsFromJwt(jwt, authorities);
            Long userId = tryParseLong(jwt.getSubject());
            String email = firstNonBlank(jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username"));
            Set<String> roles = extractRoles(authorities);
            return AuthorizationContext.of(userId, email, roles, permissions);
        }
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

    private static String buildCacheKey(Jwt jwt) {
        String subject = firstNonBlank(jwt.getSubject(), jwt.getClaimAsString("preferred_username"), "anonymous");
        String expiresAt = jwt.getExpiresAt() == null ? "na" : String.valueOf(jwt.getExpiresAt().getEpochSecond());
        int tokenHash = Objects.hashCode(jwt.getTokenValue());
        return subject + ":" + expiresAt + ":" + Integer.toHexString(tokenHash);
    }

    private static boolean isReusable(AuthorizationContext context) {
        return context != null && !context.getPermissions().isEmpty();
    }

}
