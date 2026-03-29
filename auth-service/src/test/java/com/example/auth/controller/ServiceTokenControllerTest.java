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
        "security.service-clients.user-service=test-secret",
        "security.service-mint.allowed-audiences=permission-service,user-service",
        "security.service-mint.allowed-permissions=permissions:read,permissions:write",
        "security.service-mint.rate-limit-per-minute=1",
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

    @org.springframework.boot.test.context.TestConfiguration
    static class ClientsTestConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        public com.example.auth.serviceauth.ServiceClientsProperties testClients() {
            com.example.auth.serviceauth.ServiceClientsProperties p = new com.example.auth.serviceauth.ServiceClientsProperties();
            java.util.Map<String, String> m = new java.util.HashMap<>();
            m.put("user-service", "test-secret");
            m.put("rate-client", "test-secret2");
            p.setClients(m);
            return p;
        }
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
    void mint_invalid_client_401() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("user-service", "wrong"))
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
        // First OK with a fresh client (isolated from previous tests)
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("rate-client", "test-secret2"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=permission-service"))
                .andExpect(status().isOk());
        // Second should hit rate limit
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("rate-client", "test-secret2"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=permission-service"))
                .andExpect(status().isTooManyRequests());
    }
}
