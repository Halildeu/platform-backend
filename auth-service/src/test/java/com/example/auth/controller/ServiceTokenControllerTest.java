package com.example.auth.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // Allow a known client and mint policy for tests
        "security.service-clients.clients.user-service.secret=test-secret",
        "security.service-clients.clients.user-service.allowed-audiences[0]=permission-service",
        "security.service-clients.clients.user-service.allowed-audiences[1]=notification-orchestrator",
        "security.service-clients.clients.user-service.allowed-permissions[0]=permissions:read",
        "security.service-clients.clients.user-service.allowed-permissions[1]=notify:intents:system",
        "security.service-clients.clients.user-service.require-explicit-permissions=false",
        "security.service-clients.clients.ethics-service.secret=ethics-test-secret",
        "security.service-clients.clients.ethics-service.allowed-audiences[0]=notification-orchestrator",
        "security.service-clients.clients.ethics-service.allowed-permissions[0]=notify:intents:system",
        "security.service-clients.clients.ethics-service.allowed-permissions-by-audience[notification-orchestrator][0]=notify:intents:system",
        "security.service-clients.clients.ethics-service.require-explicit-permissions=true",
        "security.service-clients.clients.rate-client.secret=test-secret2",
        "security.service-clients.clients.rate-client.allowed-audiences[0]=permission-service",
        "security.service-clients.clients.rate-client.require-explicit-permissions=false",
        "security.service-mint.allowed-audiences=permission-service,user-service,notification-orchestrator",
        "security.service-mint.allowed-permissions=permissions:read,permissions:write,notify:intents:system",
        "security.service-mint.rate-limit-per-minute=2",
        "security.service-mint.failed-auth-rate-limit-per-minute=100",
        "auth.impersonation.keycloak-token-url=http://localhost:9999/token",
        "auth.impersonation.keycloak-broker-url=http://localhost:9999/broker",
        // H2 ile test, discovery kapalı
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        // Vault/actuator health bağımlılıklarını kapat
        "spring.cloud.vault.enabled=false",
        "management.health.vault.enabled=false",
        // Bean override izni (gerekirse)
        "spring.main.allow-bean-definition-overriding=true"
})
class ServiceTokenControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private String basic(String clientId, String secret) {
        String raw = clientId + ":" + secret;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void mint_success_with_valid_client_and_audience() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("user-service", "test-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=permission-service&permissions=permissions:read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(jsonPath("$.token_type").value("Bearer"));
    }

    @Test
    void mint_success_notificationOrchestrator_systemPermission() throws Exception {
        // #734: user-service mints a notification-orchestrator-audience token with
        // notify:intents:system for the internal admin-email system-submit path.
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("user-service", "test-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=notification-orchestrator&permissions=notify:intents:system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(jsonPath("$.token_type").value("Bearer"));
    }

    @Test
    void ethicsServiceCanMintOnlyExplicitNotificationPermission() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("ethics-service", "ethics-test-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=notification-orchestrator&permissions=notify:intents:system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(jsonPath("$.token_type").value("Bearer"));

        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("ethics-service", "ethics-test-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=notification-orchestrator&permissions=permissions:write"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mint_invalid_client_401() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("user-service", "wrong"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=permission-service"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedBasicHeader_isRejectedAsInvalidClient() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", "Basic not-base64%%%")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=permission-service"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mint_invalid_audience_400() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("user-service", "test-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=not-allowed"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mint_invalid_permission_400() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("user-service", "test-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=permission-service&permissions=not-allowed"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mint_rate_limited_429() throws Exception {
        // Two requests fit the configured per-client window.
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("rate-client", "test-secret2"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=permission-service"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("rate-client", "test-secret2"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=permission-service"))
                .andExpect(status().isOk());

        // The third request in the same window must be rejected.
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("rate-client", "test-secret2"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=permission-service"))
                .andExpect(status().isTooManyRequests());
    }
}
