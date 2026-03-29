package com.example.commonauth;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class AuthorizationContextBuilder {

    private AuthorizationContextBuilder() {
    }

    public static AuthorizationContext fromJwt(Jwt jwt) {
        if (jwt == null) {
            return AuthorizationContext.of(null, null, Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        }

        Set<String> roles = new HashSet<>();
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null) {
            Object realmRoles = realmAccess.get("roles");
            if (realmRoles instanceof Collection<?> collection) {
                collection.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .forEach(roles::add);
            }
        }

        // Permissions no longer extracted from JWT — they come from OpenFGA via ScopeContext.
        // JWT is identity-only: sub, email, realm_access.roles
        Set<String> permissions = Set.of();

        Long userId = extractUserId(jwt);
        String email = jwt.getClaimAsString("email");

        return AuthorizationContext.of(userId, email, roles, permissions, Set.of(), Set.of(), Set.of());
    }

    private static Long extractUserId(Jwt jwt) {
        Object userIdClaim = jwt.getClaim("userId");
        if (userIdClaim instanceof Number number) {
            return number.longValue();
        }
        if (userIdClaim instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                // fall back to subject
            }
        }
        String sub = Optional.ofNullable(jwt.getSubject()).orElse(null);
        if (sub != null) {
            try {
                return Long.parseLong(sub);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
