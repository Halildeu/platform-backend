package com.example.budget.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
        classes = BudgetErrorDispatchSecurityIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=test",
            "server.error.include-message=never",
            "server.error.include-stacktrace=never"
        })
class BudgetErrorDispatchSecurityIntegrationTest {
    private static final String PLANNER_TOKEN = "planner-token";
    private static final String UNSCOPED_TOKEN = "unscoped-token";

    @LocalServerPort
    private int port;

    private final TestRestTemplate http = new TestRestTemplate();

    @Test
    void authorizedNotFoundSurvivesTheRealServletErrorDispatch() {
        ResponseEntity<String> response =
                get("/api/v1/budgets/error-contract/not-found", PLANNER_TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody())
                .doesNotContain("Missing test resource")
                .doesNotContain("\"trace\"")
                .doesNotContain("\"stackTrace\"");
    }

    @Test
    void authorizedBadRequestIsNotMaskedAsForbidden() {
        ResponseEntity<String> response =
                get("/api/v1/budgets/error-contract/bad-request", PLANNER_TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .doesNotContain("Invalid test input")
                .doesNotContain("\"trace\"")
                .doesNotContain("\"stackTrace\"");
    }

    @Test
    void unscopedBudgetRequestRemainsForbidden() {
        ResponseEntity<String> response =
                get("/api/v1/budgets/error-contract/not-found", UNSCOPED_TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void directErrorRequestDispatchRemainsDenied() {
        ResponseEntity<String> response = get("/error", PLANNER_TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(
                "http://127.0.0.1:" + port + path,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SecurityConfig.class, ErrorContractController.class})
    static class TestApplication {
        @Bean
        JwtDecoder testJwtDecoder() {
            return token -> jwt(token, PLANNER_TOKEN.equals(token));
        }

        private Jwt jwt(String token, boolean scopedPlanner) {
            Jwt.Builder jwt = Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("test-user")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .claim("tenant_id", "test-tenant");
            if (scopedPlanner) {
                jwt.claim("scope", "openid budget:read budget:write")
                        .claim("realm_access", Map.of("roles", List.of("budget-planner")));
            } else {
                jwt.claim("scope", "openid")
                        .claim("realm_access", Map.of("roles", List.of("budget-planner")));
            }
            return jwt.build();
        }
    }

    @RestController
    static class ErrorContractController {
        @GetMapping("/api/v1/budgets/error-contract/not-found")
        @PreAuthorize("hasAuthority('SCOPE_budget:read') and hasAuthority('ROLE_BUDGET_PLANNER')")
        void notFound() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Missing test resource");
        }

        @GetMapping("/api/v1/budgets/error-contract/bad-request")
        @PreAuthorize("hasAuthority('SCOPE_budget:read') and hasAuthority('ROLE_BUDGET_PLANNER')")
        void badRequest() {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid test input");
        }
    }
}
