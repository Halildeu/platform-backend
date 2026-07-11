package com.example.auth.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ai#244 AI-1: proves auth-service can mint the service token meeting-ai needs to push
 * analysis results — {@code audience=meeting-service} carrying
 * {@code meeting:analysis-result:write}. Both must be on the mint allowlist (this PR
 * added them to the application-k8s defaults) or auth-service rejects with 400.
 *
 * <p>Deliberately its own context (not folded into {@link ServiceTokenControllerTest})
 * so it neither depends on nor is broken by that suite's pre-existing rate-limit=1 /
 * impersonation-placeholder fragility (tracked separately). rate-limit is generous here
 * so the positive + negative cases both run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "security.service-clients.meeting-ai=test-secret",
        "security.service-mint.allowed-audiences=meeting-service",
        "security.service-mint.allowed-permissions=meeting:analysis-result:write",
        "security.service-mint.rate-limit-per-minute=100",
        "auth.impersonation.keycloak-token-url=http://localhost:9999/token",
        "auth.impersonation.keycloak-broker-url=http://localhost:9999/broker",
        "spring.datasource.url=jdbc:h2:mem:maitok;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.vault.enabled=false",
        "management.health.vault.enabled=false",
        "spring.main.allow-bean-definition-overriding=true"
})
class MeetingAiServiceTokenMintTest {

    @Autowired
    private MockMvc mockMvc;

    private static String basic(String clientId, String secret) {
        String raw = clientId + ":" + secret;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @TestConfiguration
    static class ClientsTestConfig {
        @Bean
        @Primary
        com.example.auth.serviceauth.ServiceClientsProperties testClients() {
            com.example.auth.serviceauth.ServiceClientsProperties p =
                    new com.example.auth.serviceauth.ServiceClientsProperties();
            Map<String, String> m = new HashMap<>();
            m.put("meeting-ai", "test-secret");
            p.setClients(m);
            return p;
        }
    }

    @Test
    void meetingAi_mints_meetingService_analysisResultWrite() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("meeting-ai", "test-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=meeting-service"
                                + "&permissions=meeting:analysis-result:write"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.token_type").value("Bearer"));
    }

    @Test
    void meetingAi_wrongAudience_isRejected() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("meeting-ai", "test-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=not-allowed"
                                + "&permissions=meeting:analysis-result:write"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void meetingAi_unlistedPermission_isRejected() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("meeting-ai", "test-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=meeting-service"
                                + "&permissions=permissions:write"))
                .andExpect(status().isBadRequest());
    }
}
