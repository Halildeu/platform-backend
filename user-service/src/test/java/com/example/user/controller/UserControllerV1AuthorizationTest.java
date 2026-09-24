package com.example.user.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.user.UserApplication;
import com.example.user.config.TestSecurityConfig;
import com.example.user.model.User;
import com.example.user.repository.UserAuditEventRepository;
import com.example.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * gitops#3839 — function-level authorization on the user detail and activation surfaces.
 *
 * <p>Measured on TEST: a synthetic budget planner (no user-management permission, 403 on the
 * admin grid) activated another account through {@code PUT /api/v1/users/{id}/activation} and
 * could read any account's detail through {@code GET /api/v1/users/{id}} / {@code /by-email}.
 * Every caller here holds NO user-management permission (permission-service answers an empty
 * set); the admin path keeps its coverage in {@link UserControllerV1Test}, whose callers hold
 * {@code VIEW_USERS}/{@code MANAGE_USERS}.
 */
@SpringBootTest(classes = {UserApplication.class, UserControllerV1AuthorizationTest.NoUserManagementUpstream.class},
        webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@TestPropertySource(properties = {
        "SECURITY_JWT_ISSUER=auth-service",
        "SECURITY_JWT_AUDIENCE=user-service,frontend",
        "spring.datasource.url=jdbc:h2:mem:testdb-authz3839;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.main.allow-bean-definition-overriding=true",
        // The suite-wide dev scope is super-admin, which bypasses every permission check
        // (requirePermissionWithCompanyScope returns early). A regular employee is not.
        "erp.openfga.dev-scope.super-admin=false"
})
class UserControllerV1AuthorizationTest {

    /** permission-service stand-in: a regular employee — no user-management permission at all. */
    @org.springframework.boot.test.context.TestConfiguration
    static class NoUserManagementUpstream {
        @org.springframework.context.annotation.Bean(name = "plainWebClientBuilder")
        @org.springframework.context.annotation.Primary
        org.springframework.web.reactive.function.client.WebClient.Builder stubPlainWebClientBuilder() {
            return org.springframework.web.reactive.function.client.WebClient.builder()
                    .exchangeFunction(request -> {
                        String body = request.url().getPath().endsWith("/api/v1/authz/version")
                                ? "{\"authzVersion\":1}"
                                : "{\"userId\":\"30\",\"permissions\":[\"budget:plan\"],"
                                        + "\"allowedScopes\":[],\"superAdmin\":false}";
                        return reactor.core.publisher.Mono.just(
                                org.springframework.web.reactive.function.client.ClientResponse
                                        .create(org.springframework.http.HttpStatus.OK)
                                        .header("Content-Type", "application/json")
                                        .body(body)
                                        .build());
                    });
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private UserAuditEventRepository userAuditEventRepository;
    @Autowired private ObjectMapper objectMapper;

    private User caller;
    private User other;

    @BeforeEach
    void seed() {
        userAuditEventRepository.deleteAll();
        userRepository.deleteAll();
        caller = save("planner@example.com", true);
        other = save("someone.else@example.com", false);
    }

    private User save(String email, boolean enabled) {
        User user = new User();
        user.setEmail(email);
        user.setName(email);
        user.setPassword("x");
        user.setRole("USER");
        user.setEnabled(enabled);
        return userRepository.save(user);
    }

    private String token(String email) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(email)
                .issuer("auth-service")
                .audience(List.of("user-service"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("email", email)
                .build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    @Test
    void activatingAnotherAccountWithoutUserUpdateIsRefusedAndChangesNothing() throws Exception {
        mockMvc.perform(put("/api/v1/users/{id}/activation", other.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(caller.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", true))))
                .andExpect(status().isForbidden());

        Assertions.assertThat(userRepository.findById(other.getId()).orElseThrow().isEnabled()).isFalse();
        Assertions.assertThat(userAuditEventRepository.count()).isZero();
    }

    @Test
    void deactivatingAnotherAccountWithoutUserUpdateIsRefused() throws Exception {
        other.setEnabled(true);
        userRepository.save(other);

        mockMvc.perform(put("/api/v1/users/{id}/activation", other.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(caller.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", false))))
                .andExpect(status().isForbidden());

        Assertions.assertThat(userRepository.findById(other.getId()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void adminGridWithoutUserReadIsRefused() throws Exception {
        // The suite-wide super-admin dev scope meant this gate was never exercised for a regular
        // employee; it is the reference the fixed endpoints now align with.
        mockMvc.perform(get("/api/v1/users").param("search", "someone")
                        .header(HttpHeaders.AUTHORIZATION, token(caller.getEmail())))
                .andExpect(status().isForbidden());
    }

    @Test
    void readingAnotherAccountByIdWithoutUserReadIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}", other.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(caller.getEmail())))
                .andExpect(status().isForbidden());
    }

    @Test
    void readingAnotherAccountByEmailWithoutUserReadIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/users/by-email").param("email", other.getEmail())
                        .header(HttpHeaders.AUTHORIZATION, token(caller.getEmail())))
                .andExpect(status().isForbidden());
    }

    @Test
    void anyoneMayReadTheirOwnRecordByIdOrEmail() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}", caller.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(caller.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(caller.getEmail()));
        mockMvc.perform(get("/api/v1/users/by-email").param("email", "Planner@Example.com")
                        .header(HttpHeaders.AUTHORIZATION, token(caller.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(caller.getEmail()));
    }
}
