package com.example.user.controller;

import com.example.user.UserApplication;
import com.example.user.config.TestSecurityConfig;
import com.example.user.model.User;
import com.example.user.repository.UserNotificationPreferenceRepository;
import com.example.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class NotificationPreferencesControllerV1Test {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserNotificationPreferenceRepository userNotificationPreferenceRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDb() {
        userNotificationPreferenceRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void getPreferences_invalidToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/notification-preferences")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getPreferences_missingLocalProfile_returns403() throws Exception {
        String token = issueToken("missing@example.com");
        mockMvc.perform(get("/api/v1/notification-preferences")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("PROFILE_MISSING")));
    }

    @Test
    void getPreferences_existingProfile_returnsDefaults() throws Exception {
        User existing = ensureUserExists("user@example.com");
        String token = issueToken(existing.getEmail());
        mockMvc.perform(get("/api/v1/notification-preferences")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences", hasSize(4)))
                .andExpect(jsonPath("$.preferences[0].channel").isNotEmpty());
    }

    @Test
    void patchPreferences_unknownChannel_returns400() throws Exception {
        User existing = ensureUserExists("user@example.com");
        String token = issueToken(existing.getEmail());
        Map<String, Object> body = Map.of(
                "preferences", List.of(Map.of("channel", "fax", "enabled", true))
        );
        mockMvc.perform(patch("/api/v1/notification-preferences")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("unknown_channel")));
    }

    @Test
    void patchPreferences_updatesAndReturnsPreferences() throws Exception {
        User existing = ensureUserExists("user@example.com");
        String token = issueToken(existing.getEmail());
        Map<String, Object> body = Map.of(
                "preferences", List.of(
                        Map.of("channel", "email", "enabled", false),
                        Map.of("channel", "in_app", "enabled", true, "frequency", "daily")
                )
        );
        mockMvc.perform(patch("/api/v1/notification-preferences")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences", hasSize(4)))
                .andExpect(content().string(containsString("\"channel\":\"email\"")))
                .andExpect(content().string(containsString("\"enabled\":false")));
    }

    @Test
    void patchPreferences_invalidJson_returns400() throws Exception {
        User existing = ensureUserExists("user@example.com");
        String token = issueToken(existing.getEmail());
        mockMvc.perform(patch("/api/v1/notification-preferences")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("ERR_BAD_REQUEST")));
    }

    private User ensureUserExists(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User();
            user.setEmail(email);
            user.setName(email);
            user.setPassword("x");
            user.setEnabled(true);
            user.setRole("USER");
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

