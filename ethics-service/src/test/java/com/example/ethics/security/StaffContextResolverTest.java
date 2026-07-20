package com.example.ethics.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class StaffContextResolverTest {
    private final EthicsEntitlementVerifier entitlements = mock(EthicsEntitlementVerifier.class);
    private final StaffContextResolver resolver = new StaffContextResolver(entitlements);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validJwtStillRequiresCurrentServerSideEntitlement() {
        UUID orgId = UUID.randomUUID();
        Jwt jwt = jwt(orgId);
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(jwt, null));

        when(entitlements.hasManageEntitlement("staff-token")).thenReturn(false);
        assertThatThrownBy(resolver::required)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(403);

        when(entitlements.hasManageEntitlement("staff-token")).thenReturn(true);
        assertThat(resolver.required()).isEqualTo(new StaffContext(orgId, "staff-subject"));
    }

    private Jwt jwt(UUID orgId) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("staff-token")
                .header("alg", "none")
                .subject("staff-subject")
                .claim("org_id", orgId.toString())
                .issuedAt(now.minusSeconds(5))
                .expiresAt(now.plusSeconds(300))
                .build();
    }
}
