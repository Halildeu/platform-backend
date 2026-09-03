package com.example.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Faz 24 Görevler dilim-4b (gitops#3486 / #3537) — meeting-service may submit
 * assignment notifications as system intents and nothing else against the
 * orchestrator; the orchestrator audience must not leak the directory
 * permission and the directory audience must not gain the intent permission.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "security.service-clients.clients.meeting-service.secret=meeting-secret",
        "security.service-clients.clients.meeting-service.allowed-audiences[0]=transcript-service",
        "security.service-clients.clients.meeting-service.allowed-audiences[1]=user-service",
        "security.service-clients.clients.meeting-service.allowed-audiences[2]=notification-orchestrator",
        "security.service-clients.clients.meeting-service.allowed-permissions[0]=transcript:session:erase",
        "security.service-clients.clients.meeting-service.allowed-permissions[1]=transcript:canonical:read",
        "security.service-clients.clients.meeting-service.allowed-permissions[2]=users:internal",
        "security.service-clients.clients.meeting-service.allowed-permissions[3]=notify:intents:system",
        "security.service-clients.clients.meeting-service.allowed-permissions-by-audience[transcript-service][0]=transcript:session:erase",
        "security.service-clients.clients.meeting-service.allowed-permissions-by-audience[transcript-service][1]=transcript:canonical:read",
        "security.service-clients.clients.meeting-service.allowed-permissions-by-audience[user-service][0]=users:internal",
        "security.service-clients.clients.meeting-service.allowed-permissions-by-audience[notification-orchestrator][0]=notify:intents:system",
        "security.service-clients.clients.meeting-service.require-explicit-permissions=true",
        "security.service-mint.allowed-audiences=transcript-service,user-service,notification-orchestrator",
        "security.service-mint.allowed-permissions=transcript:session:erase,transcript:canonical:read,users:internal,notify:intents:system",
        "security.service-mint.rate-limit-per-minute=100",
        "security.service-mint.failed-auth-rate-limit-per-minute=1000",
        "auth.impersonation.keycloak-token-url=http://localhost:9999/token",
        "auth.impersonation.keycloak-broker-url=http://localhost:9999/broker",
        "spring.datasource.url=jdbc:h2:mem:mtgnotifytok;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
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
class MeetingServiceNotifyTokenMintTest {

    @Autowired
    private MockMvc mockMvc;

    private static String basic(String clientId, String secret) {
        String raw = clientId + ":" + secret;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static JWTClaimsSet claims(MvcResult result) throws Exception {
        String token = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result.getResponse().getContentAsString()).get("access_token").asText();
        return SignedJWT.parse(token).getJWTClaimsSet();
    }

    @Test
    void meetingService_mints_orchestrator_systemIntent() throws Exception {
        MvcResult result = mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("meeting-service", "meeting-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=notification-orchestrator"
                                + "&permissions=notify:intents:system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andReturn();
        JWTClaimsSet claims = claims(result);
        assertEquals("meeting-service", claims.getSubject());
        assertEquals(java.util.List.of("notification-orchestrator"), claims.getAudience());
        assertEquals(java.util.List.of("notify:intents:system"), claims.getStringListClaim("perm"));
    }

    /** The orchestrator credential must not carry directory authority. */
    @Test
    void meetingService_isRefused_usersInternalAgainstOrchestrator() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("meeting-service", "meeting-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=notification-orchestrator"
                                + "&permissions=users:internal"))
                .andExpect(status().isBadRequest());
    }

    /** And the directory credential must not gain intent authority. */
    @Test
    void meetingService_isRefused_systemIntentAgainstUserService() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("meeting-service", "meeting-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=user-service"
                                + "&permissions=notify:intents:system"))
                .andExpect(status().isBadRequest());
    }
}
