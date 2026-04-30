package com.example.variant.authz;

import com.example.commonauth.AuthorizationContext;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantAuthorizationServiceImplTest {

    @Test
    void buildsContextWithPermissionsAndProjects() {
        CountingStubClient client = new CountingStubClient();
        client.setResponse(buildAuthzMeResponse());

        VariantAuthorizationServiceImpl service = new VariantAuthorizationServiceImpl(client, Duration.ofSeconds(1));

        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("42")
                .claim("email", "u@example.com")
                .claim("permissions", List.of("VARIANTS_READ"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        AuthorizationContext ctx = service.buildContext(jwt);
        assertEquals(42L, ctx.getUserId());
        assertTrue(ctx.hasPermission("VARIANTS_READ"));
        assertThat(ctx.getAllowedProjectIds()).containsExactlyInAnyOrder(101L, 102L);
        assertEquals(1, client.callCount.get());

        // cache hit should not call client again
        service.buildContext(jwt);
        assertEquals(1, client.callCount.get());
    }

    @Test
    void buildsContextFromAuthServiceStyleJwtClaims() {
        CountingStubClient client = new CountingStubClient();
        client.setResponse(new AuthzMeResponse());

        VariantAuthorizationServiceImpl service = new VariantAuthorizationServiceImpl(client, Duration.ofSeconds(1));

        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("admin@example.com")
                .claim("uid", 1201)
                .claim("email", "admin@example.com")
                .claim("role", "ADMIN")
                .claim("permissions", List.of("audit-read"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        AuthorizationContext ctx = service.buildContext(jwt);
        assertEquals(1201L, ctx.getUserId());
        assertThat(ctx.getEmail()).isEqualTo("admin@example.com");
        assertThat(ctx.getRoles()).contains("ADMIN");
        assertThat(ctx.isAdmin()).isTrue();
    }

    @Test
    void buildsContextFromAuthzRolesWhenJwtRolesMissing() {
        CountingStubClient client = new CountingStubClient();
        AuthzMeResponse response = new AuthzMeResponse();
        response.setUserId("42");
        response.setRoles(List.of("ADMIN"));
        client.setResponse(response);

        VariantAuthorizationServiceImpl service = new VariantAuthorizationServiceImpl(client, Duration.ofSeconds(1));

        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("42")
                .claim("email", "u@example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        AuthorizationContext ctx = service.buildContext(jwt);
        assertThat(ctx.getRoles()).contains("ADMIN");
        assertThat(ctx.isAdmin()).isTrue();
    }

    @Test
    void buildsContextAsAdminWhenAuthzSuperAdminTrue() {
        CountingStubClient client = new CountingStubClient();
        AuthzMeResponse response = new AuthzMeResponse();
        response.setUserId("42");
        response.setSuperAdmin(Boolean.TRUE);
        client.setResponse(response);

        VariantAuthorizationServiceImpl service = new VariantAuthorizationServiceImpl(client, Duration.ofSeconds(1));

        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("42")
                .claim("email", "u@example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        AuthorizationContext ctx = service.buildContext(jwt);
        assertThat(ctx.getRoles()).contains("ADMIN");
        assertThat(ctx.isAdmin()).isTrue();
    }

    private AuthzMeResponse buildAuthzMeResponse() {
        AuthzMeResponse response = new AuthzMeResponse();
        response.setUserId("42");
        // Permissions now come from authz service (ADR-003: JWT is identity-only)
        response.setPermissions(List.of("VARIANTS_READ"));
        response.setAllowedScopes(List.of(
                new ScopeSummaryDto("PROJECT", "101"),
                new ScopeSummaryDto("PROJECT", "102")
        ));
        return response;
    }

    // Codex 019dddb7 iter-42 — upstream failure must NOT be cached.
    // Pre-iter-42 PermissionServiceAuthzClient returned an empty
    // AuthzMeResponse on any failure path; the cache.get supplier in
    // VariantAuthorizationServiceImpl wrapped that empty result through
    // Optional.ofNullable(...).orElse(...) and stored it. Subsequent
    // requests within the TTL would replay the empty context, even after
    // permission-service had recovered. The new client throws typed
    // exceptions, which propagate out of the supplier and skip the cache
    // write — proven here by issuing a transient failure followed by a
    // successful response and asserting the second call was not served
    // from the empty-cache state.
    @Test
    void doesNotCacheUpstreamFailures() {
        CountingStubClient client = new CountingStubClient();
        AuthzMeResponse goodResponse = buildAuthzMeResponse();
        client.queueException(
                new AuthzDependencyUnavailableException("permission-service down")
        );
        client.queueResponse(goodResponse);

        VariantAuthorizationServiceImpl service =
                new VariantAuthorizationServiceImpl(client, Duration.ofSeconds(60));

        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("42")
                .claim("email", "u@example.com")
                .claim("permissions", List.of("VARIANTS_READ"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        // First call: upstream fails, exception propagates (no cache write).
        org.junit.jupiter.api.Assertions.assertThrows(
                AuthzDependencyUnavailableException.class,
                () -> service.buildContext(jwt)
        );
        assertEquals(1, client.callCount.get());

        // Second call: upstream now healthy. Cache must NOT serve the
        // earlier failure — the supplier should run again.
        AuthorizationContext ctx = service.buildContext(jwt);
        assertEquals(42L, ctx.getUserId());
        assertEquals(2, client.callCount.get());
    }

    private static class CountingStubClient extends PermissionServiceAuthzClient {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private AuthzMeResponse response = new AuthzMeResponse();
        private final java.util.Deque<Object> queue = new java.util.ArrayDeque<>();

        CountingStubClient() {
            super(org.springframework.web.reactive.function.client.WebClient.builder());
        }

        void setResponse(AuthzMeResponse response) {
            this.response = response;
        }

        void queueResponse(AuthzMeResponse response) {
            queue.addLast(response);
        }

        void queueException(RuntimeException ex) {
            queue.addLast(ex);
        }

        @Override
        public AuthzMeResponse getAuthzMe(String bearerToken) {
            callCount.incrementAndGet();
            if (!queue.isEmpty()) {
                Object next = queue.pollFirst();
                if (next instanceof RuntimeException ex) throw ex;
                return (AuthzMeResponse) next;
            }
            return response;
        }
    }
}
