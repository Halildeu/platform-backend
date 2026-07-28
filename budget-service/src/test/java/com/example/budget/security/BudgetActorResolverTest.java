package com.example.budget.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.budget.security.BudgetAuthorizationClient.AuthorizationSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class BudgetActorResolverTest {

    @Test
    void usesAuthoritativeAuthzScopeInsteadOfJwtProjectClaims() {
        RecordingClient client = new RecordingClient(new AuthorizationSnapshot(
                "1204", Set.of(35L), Set.of(44200L), false));
        BudgetActorResolver resolver = new BudgetActorResolver(client);

        BudgetActor actor = resolver.resolve(authentication(jwt()
                .claim("company_ids", List.of(999L))
                .claim("project_ids", List.of(999L))
                .build()), 35L);

        assertThat(actor.subject()).isEqualTo("1204");
        assertThat(actor.allowedProjectIds()).containsExactly(44200L);
        assertThat(actor.canAccessProject(999L)).isFalse();
        assertThat(client.token).isEqualTo("browser-token");
    }

    @Test
    void deniesCompanyOutsideAuthoritativeScope() {
        BudgetActorResolver resolver = new BudgetActorResolver(token ->
                new AuthorizationSnapshot("1204", Set.of(38L), Set.of(44200L), false));

        assertThatThrownBy(() -> resolver.resolve(authentication(jwt().build()), 35L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authoritative scope");
    }

    @Test
    void superAdminMaySelectCompanyWithoutSyntheticJwtClaims() {
        BudgetActorResolver resolver = new BudgetActorResolver(token ->
                new AuthorizationSnapshot("1", Set.of(), Set.of(), true));

        BudgetActor actor = resolver.resolve(authentication(jwt().build()), 35L);

        assertThat(actor.superAdmin()).isTrue();
        assertThat(actor.companyId()).isEqualTo(35L);
    }

    private Jwt.Builder jwt() {
        return Jwt.withTokenValue("browser-token")
                .header("alg", "none")
                .subject("keycloak-subject")
                .claim("tenant_id", "tenant-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
    }

    private JwtAuthenticationToken authentication(Jwt jwt) {
        return new JwtAuthenticationToken(jwt);
    }

    private static final class RecordingClient implements BudgetAuthorizationClient {
        private final AuthorizationSnapshot response;
        private String token;

        private RecordingClient(AuthorizationSnapshot response) {
            this.response = response;
        }

        @Override
        public AuthorizationSnapshot fetch(String bearerToken) {
            this.token = bearerToken;
            return response;
        }
    }
}
