package com.example.budget.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class BudgetJwtAuthenticationConverterTest {
    private final SecurityConfig securityConfig = new SecurityConfig();

    @Test
    void keepsScopesAndMapsOnlyPresentedRealmRoles() {
        Jwt jwt = jwt(Map.of(
                "scope", "openid budget:read budget:write",
                "realm_access", Map.of("roles", List.of("budget-planner", "offline_access"))));

        JwtAuthenticationToken authentication =
                (JwtAuthenticationToken) securityConfig.budgetJwtAuthenticationConverter().convert(jwt);

        assertThat(authorities(authentication))
                .contains("SCOPE_budget:read", "SCOPE_budget:write", "ROLE_BUDGET_PLANNER")
                .doesNotContain("ROLE_BUDGET_APPROVER");
    }

    @Test
    void scopeAloneNeverSynthesizesPlannerOrApproverRole() {
        Jwt jwt = jwt(Map.of("scope", "openid budget:read budget:write budget:approve"));

        JwtAuthenticationToken authentication =
                (JwtAuthenticationToken) securityConfig.budgetJwtAuthenticationConverter().convert(jwt);

        assertThat(authorities(authentication))
                .contains("SCOPE_budget:read", "SCOPE_budget:write", "SCOPE_budget:approve")
                .doesNotContain("ROLE_BUDGET_PLANNER", "ROLE_BUDGET_APPROVER");
    }

    private Set<String> authorities(JwtAuthenticationToken authentication) {
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .collect(Collectors.toSet());
    }

    private Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("test-user")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
