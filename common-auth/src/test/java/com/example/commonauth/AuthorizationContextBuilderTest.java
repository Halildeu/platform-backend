package com.example.commonauth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizationContextBuilderTest {

    @Test
    void buildsContextFromJwt() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("userId", 42)
                .claim("email", "user@example.com")
                .claim("realm_access", Map.of("roles", Set.of("admin", "user")))
                .claim("permissions", Set.of("MANAGE_GLOBAL_VARIANTS", "VARIANTS_WRITE", "ADMIN"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        AuthorizationContext ctx = AuthorizationContextBuilder.fromJwt(jwt);

        assertEquals(42L, ctx.getUserId());
        assertEquals("user@example.com", ctx.getEmail());
        // Permissions set from collection and string-lists are covered; builder should tolerate mixed formats.
        assertTrue(ctx.hasPermission("VARIANTS_WRITE"));
        assertTrue(ctx.isAdmin());
        assertTrue(ctx.getAllowedCompanyIds().isEmpty());
        assertTrue(ctx.getAllowedProjectIds().isEmpty());
        assertTrue(ctx.getAllowedWarehouseIds().isEmpty());
    }

    @Test
    void toleratesStringPermissionsClaim() {
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
        assertTrue(ctx.hasPermission(PermissionCodes.VARIANTS_WRITE));
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
