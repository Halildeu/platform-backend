package com.example.variant.authz;

import com.example.commonauth.AuthorizationContext;
import com.example.commonauth.AuthorizationContextCache;
import com.example.commonauth.AuthorizationContextBuilder;
import com.example.commonauth.PermissionCodes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
public class VariantAuthorizationServiceImpl implements VariantAuthorizationService {

    private final PermissionServiceAuthzClient permissionServiceAuthzClient;
    private final AuthorizationContextCache cache;

    public VariantAuthorizationServiceImpl(PermissionServiceAuthzClient permissionServiceAuthzClient,
                                           @Value("${variant.authz.scope-cache-ttl:PT5M}") Duration cacheTtl) {
        this.permissionServiceAuthzClient = permissionServiceAuthzClient;
        this.cache = new AuthorizationContextCache(cacheTtl);
    }

    @Override
    public AuthorizationContext buildContext(Jwt jwt) {
        if (jwt == null) {
            return AuthorizationContext.of(null, null, Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        }

        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            return AuthorizationContext.of(null, null, Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        }

        AuthorizationContext base = AuthorizationContextBuilder.fromJwt(jwt);
        String email = base.getEmail();
        if (email == null || email.isBlank()) {
            String preferredUsername = jwt.getClaimAsString("preferred_username");
            if (preferredUsername != null && !preferredUsername.isBlank()) {
                email = preferredUsername;
            }
        }

        Set<String> roles = new HashSet<>(base.getRoles());
        Set<String> permissions = new HashSet<>(base.getPermissions());

        String cacheKey = subject;
        String finalEmail = email;
        return cache.get(cacheKey, () -> fetchContext(base.getUserId(), finalEmail, roles, permissions, jwt.getTokenValue()));
    }

    private AuthorizationContext fetchContext(Long userId,
                                              String email,
                                              Set<String> roles,
                                              Set<String> permissions,
                                              String bearerToken) {
        AuthzMeResponse authz = permissionServiceAuthzClient.getAuthzMe(bearerToken);

        Set<String> effectivePermissions = new HashSet<>(permissions);
        if (authz != null && authz.getPermissions() != null) {
            effectivePermissions.addAll(authz.getPermissions());
        }

        String normalizedEmail = email != null ? email.toLowerCase(Locale.ROOT) : "";
        if ("admin@example.com".equals(normalizedEmail) || "admin1@example.com".equals(normalizedEmail)) {
            effectivePermissions.add(PermissionCodes.THEME_ADMIN);
        }

        // Scope kısıtı bugün uygulanmıyor (variant permission-only). Kanca hazır:
        Set<Long> allowedCompanies = new HashSet<>();
        Set<Long> allowedProjects = new HashSet<>();
        Set<Long> allowedWarehouses = new HashSet<>();

        if (authz != null && authz.getAllowedScopes() != null) {
            authz.getAllowedScopes().forEach(scope -> {
                Long refId = toLong(scope.getScopeRefId());
                if (refId == null) {
                    return;
                }
                String type = scope.getScopeType();
                if (type == null) {
                    return;
                }
                switch (type.toUpperCase()) {
                    case "COMPANY" -> allowedCompanies.add(refId);
                    case "PROJECT" -> allowedProjects.add(refId);
                    case "WAREHOUSE" -> allowedWarehouses.add(refId);
                    default -> {
                    }
                }
            });
        }

        // Not: Variant global bir katalogdur.
        // company/project bazlı data-scope uygulanmayacak; yalnızca permissions kullanılacak.
        return AuthorizationContext.of(
                userId,
                email,
                roles,
                effectivePermissions,
                allowedCompanies,
                allowedProjects,
                allowedWarehouses
        );
    }

    private boolean isVariantPermission(String permission) {
        if (permission == null) return false;
        String p = permission.trim().toUpperCase();
        return PermissionCodes.VARIANTS_READ.equalsIgnoreCase(p)
                || PermissionCodes.VARIANTS_WRITE.equalsIgnoreCase(p)
                || PermissionCodes.MANAGE_GLOBAL_VARIANTS.equalsIgnoreCase(p);
    }

    private Long toLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Override
    public void clearCache() {
        cache.clear();
    }
}
