package com.example.permission.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticatedUserLookupServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void resolve_prefersNumericUidClaim() {
        AuthenticatedUserLookupService service = new AuthenticatedUserLookupService(jdbcTemplate, "users");
        Jwt jwt = buildJwt(Map.of(
                "uid", 42L,
                "email", "admin@example.com"
        ), "kc-user-uuid");

        var resolved = service.resolve(jwt);

        assertEquals(42L, resolved.numericUserId());
        assertEquals("42", resolved.responseUserId());
        assertEquals("admin@example.com", resolved.email());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void resolve_fallsBackToEmailLookupWhenSubjectIsNotNumeric() {
        AuthenticatedUserLookupService service = new AuthenticatedUserLookupService(jdbcTemplate, "users");
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 7L)));
        Jwt jwt = buildJwt(Map.of(
                "email", "admin@example.com"
        ), "7d31b1a8-0f4d-43d8-a5df-d7cfbb5304f4");

        var resolved = service.resolve(jwt);

        assertEquals(7L, resolved.numericUserId());
        assertEquals("7", resolved.responseUserId());
        assertEquals("admin@example.com", resolved.email());
    }

    @Test
    void resolve_returnsSubjectWhenLookupCannotResolveNumericUserId() {
        AuthenticatedUserLookupService service = new AuthenticatedUserLookupService(jdbcTemplate, "users");
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());
        Jwt jwt = buildJwt(Map.of(
                "preferred_username", "admin@example.com"
        ), "7d31b1a8-0f4d-43d8-a5df-d7cfbb5304f4");

        var resolved = service.resolve(jwt);

        assertNull(resolved.numericUserId());
        assertEquals("7d31b1a8-0f4d-43d8-a5df-d7cfbb5304f4", resolved.responseUserId());
        assertEquals("admin@example.com", resolved.email());
    }

    private static Jwt buildJwt(Map<String, Object> claims, String subject) {
        var builder = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .subject(subject);
        claims.forEach(builder::claim);
        return builder.build();
    }
}
