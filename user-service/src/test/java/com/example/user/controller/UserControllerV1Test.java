package com.example.user.controller;

import com.example.user.UserApplication;
import com.example.user.config.TestSecurityConfig;
import com.example.user.model.User;
import com.example.user.repository.UserRepository;
import com.example.user.repository.UserAuditEventRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = UserApplication.class, webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@TestPropertySource(properties = {
        "SECURITY_JWT_ISSUER=auth-service",
        "SECURITY_JWT_AUDIENCE=user-service,frontend",
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.main.allow-bean-definition-overriding=true"
})
class UserControllerV1Test {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserAuditEventRepository userAuditEventRepository;

    @BeforeEach
    void cleanDb() {
        userAuditEventRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void listUsers_invalidToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users?page=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listUsers_invalidSignature_returns401() throws Exception {
        String token = issueToken("tamper@example.com") + "x";
        mockMvc.perform(get("/api/v1/users?page=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listUsers_missingLocalProfile_returns403() throws Exception {
        String token = issueToken("missing@example.com");
        mockMvc.perform(get("/api/v1/users?page=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("PROFILE_MISSING")));
    }

    @Test
    void listUsers_existingProfile_returns200() throws Exception {
        User existing = ensureUserExists("admin@example.com");
        String token = issueToken(existing.getEmail());
        mockMvc.perform(get("/api/v1/users?page=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void listUsers_frontendAudienceToken_returns200() throws Exception {
        ensureUserExists("admin@example.com");
        String token = issueToken("admin@example.com", List.of("frontend"));
        mockMvc.perform(get("/api/v1/users?page=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void getUser_returnsDetailDto() throws Exception {
        User saved = ensureUserExists("detail@example.com");
        String token = issueToken(saved.getEmail());

        mockMvc.perform(get("/api/v1/users/{id}", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("detail@example.com")));
    }

    @Test
    void getUser_notFound_returns404() throws Exception {
        User saved = ensureUserExists("missing-profile@example.com");
        String token = issueToken(saved.getEmail());
        Long missingId = saved.getId() + 999;

        mockMvc.perform(get("/api/v1/users/{id}", missingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void getUserByEmail_returnsDetail() throws Exception {
        User saved = ensureUserExists("lookup@example.com");
        String token = issueToken(saved.getEmail());

        mockMvc.perform(get("/api/v1/users/by-email")
                        .param("email", saved.getEmail())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lookup@example.com")));
    }

    @Test
    void getUserByEmail_notFound_returns404() throws Exception {
        User saved = ensureUserExists("owner@example.com");
        String token = issueToken(saved.getEmail());

        mockMvc.perform(get("/api/v1/users/by-email")
                        .param("email", "notfound@example.com")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateActivation_returnsAckWithAudit() throws Exception {
        User saved = ensureUserExists("activate@example.com");
        saved.setEnabled(false);
        userRepository.save(saved);
        String token = issueToken(saved.getEmail());

        mockMvc.perform(put("/api/v1/users/{id}/activation", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.auditId").value(org.hamcrest.Matchers.startsWith("user-")));

        User updated = userRepository.findById(saved.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(updated.isEnabled()).isTrue();
        Assertions.assertThat(userAuditEventRepository.count()).isEqualTo(1);
    }

    @Test
    void updateUser_recordsUserAuditEvent() throws Exception {
        User saved = ensureUserExists("mutate@example.com");
        saved.setRole("USER");
        saved.setSessionTimeoutMinutes(15);
        userRepository.save(saved);
        String token = issueToken(saved.getEmail());

        mockMvc.perform(put("/api/v1/users/{id}", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "role", "ADMIN",
                                "sessionTimeoutMinutes", 16
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.sessionTimeoutMinutes").value(16));

        Assertions.assertThat(userAuditEventRepository.count()).isEqualTo(1);
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getDetails())
                .contains("role:USER->ADMIN")
                .contains("sessionTimeoutMinutes:15->16");
    }

    private User ensureUserExists(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User();
            user.setEmail(email);
            user.setName(email);
            user.setPassword("x");
            user.setEnabled(true);
            user.setRole("ADMIN");
            return userRepository.save(user);
        });
    }

    private String issueToken(String email) {
        return issueToken(email, List.of("user-service"));
    }

    private String issueToken(String email, List<String> audience) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(email)
                .issuer("auth-service")
                .audience(audience == null || audience.isEmpty() ? Collections.singletonList("user-service") : audience)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("email", email)
                .claim("permissions", List.of("VIEW_USERS", "MANAGE_USERS"))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}
