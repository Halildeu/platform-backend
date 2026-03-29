package com.example.commonauth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AuthorizationContextBuilderTest {

    @Test
    void buildsContextFromJwt_identityOnly() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("userId", 42)
                .claim("email", "user@example.com")
                .claim("realm_access", Map.of("roles", Set.of("admin", "user")))
                // JWT still has permissions claim but builder should IGNORE it
                .claim("permissions", Set.of("MANAGE_GLOBAL_VARIANTS", "VARIANTS_WRITE", "ADMIN"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        AuthorizationContext ctx = AuthorizationContextBuilder.fromJwt(jwt);

        assertEquals(42L, ctx.getUserId());
        assertEquals("user@example.com", ctx.getEmail());
        // Roles extracted from realm_access
        assertTrue(ctx.getRoles().contains("admin"));
        assertTrue(ctx.getRoles().contains("user"));
        // Permissions NOT extracted from JWT (identity-only — permissions come from OpenFGA)
        assertTrue(ctx.getPermissions().isEmpty());
        // isAdmin still works via roles
        assertTrue(ctx.isAdmin());
        assertTrue(ctx.getAllowedCompanyIds().isEmpty());
    }

    @Test
    void ignoresPermissionsClaimInJwt() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("userId", "99")
                .claim("email", "string@example.com")
                .claim("permissions", "VARIANTS_WRITE")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        AuthorizationContext ctx = AuthorizationContextBuilder.fromJwt(jwt);

        assertEquals(99L, ctx.getUserId());
        // Permissions claim ignored — empty set
        assertFalse(ctx.hasPermission(PermissionCodes.VARIANTS_WRITE));
        assertTrue(ctx.getPermissions().isEmpty());
    }

    @Test
    void cacheReturnsCachedValueUntilTtlExpires() {
        AuthorizationContextCache cache = new AuthorizationContextCache(Duration.ofMillis(100));
        AuthorizationContext first = cache.get("u1", () ->
                AuthorizationContext.of(1L, "e", Set.of(), Set.of("p1"), Set.of(10L), Set.of(), Set.of()));
        AuthorizationContext second = cache.get("u1", () ->
                AuthorizationContext.of(2L, "x", Set.of(), Set.of("p2"), Set.of(), Set.of(), Set.of()));

        assertEquals(1L, first.getUserId());
        assertEquals(1L, second.getUserId()); // cached
    }
}
