package com.example.commonauth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizationContextBuilderPermissionsTest {

    @Test
    void handlesCollectionAndStringPermissions() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("userId", 7)
                .claim("email", "perm@example.com")
                .claim("realm_access", Map.of("roles", Set.of()))
                .claim("permissions", List.of("MANAGE_GLOBAL_VARIANTS", "VARIANTS_WRITE"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        AuthorizationContext ctx = AuthorizationContextBuilder.fromJwt(jwt);

        assertTrue(ctx.getPermissions() != null);
    }
}
