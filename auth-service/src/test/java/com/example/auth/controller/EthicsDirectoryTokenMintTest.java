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
 * Faz 35 ES-203/C — the ethics-service identity may resolve display names and
 * nothing else on the user directory.
 *
 * <p>The property that matters is the third test: {@code users:internal} would
 * open {@code /internal/by-email}, which returns credential material. The
 * whole point of the dedicated {@code users:display-names:read} permission is
 * that a leaked ethics credential resolves a name and stops there — so the
 * broad permission must be REFUSED to this client even though the endpoint
 * being called would accept it from a caller entitled to hold it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "security.service-clients.clients.ethics-service.secret=ethics-secret",
        "security.service-clients.clients.ethics-service.allowed-audiences[0]=notification-orchestrator",
        "security.service-clients.clients.ethics-service.allowed-audiences[1]=user-service",
        "security.service-clients.clients.ethics-service.allowed-permissions[0]=notify:intents:system",
        "security.service-clients.clients.ethics-service.allowed-permissions[1]=users:display-names:read",
        "security.service-clients.clients.ethics-service.allowed-permissions-by-audience[notification-orchestrator][0]=notify:intents:system",
        "security.service-clients.clients.ethics-service.allowed-permissions-by-audience[user-service][0]=users:display-names:read",
        "security.service-clients.clients.ethics-service.require-explicit-permissions=true",
        "security.service-mint.allowed-audiences=notification-orchestrator,user-service",
        "security.service-mint.allowed-permissions=notify:intents:system,users:display-names:read,users:internal",
        "security.service-mint.rate-limit-per-minute=100",
        "security.service-mint.failed-auth-rate-limit-per-minute=1000",
        "auth.impersonation.keycloak-token-url=http://localhost:9999/token",
        "auth.impersonation.keycloak-broker-url=http://localhost:9999/broker",
        "spring.datasource.url=jdbc:h2:mem:ethdirtok;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
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
class EthicsDirectoryTokenMintTest {

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
    void ethicsService_mints_userService_displayNamesRead() throws Exception {
        MvcResult result = mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("ethics-service", "ethics-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=user-service"
                                + "&permissions=users:display-names:read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andReturn();

        JWTClaimsSet claims = claims(result);
        assertEquals("ethics-service", claims.getSubject());
        assertEquals(java.util.List.of("user-service"), claims.getAudience());
        assertEquals(java.util.List.of("users:display-names:read"), claims.getStringListClaim("perm"));
    }

    /** The credential that can resolve a name must not be able to read credentials. */
    @Test
    void ethicsService_isRefused_usersInternal() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("ethics-service", "ethics-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=user-service"
                                + "&permissions=users:internal"))
                .andExpect(status().isBadRequest());
    }

    /** The pairing is per-audience: display-name authority against the orchestrator is meaningless and refused. */
    @Test
    void ethicsService_isRefused_displayNamesAgainstOrchestrator() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("ethics-service", "ethics-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=notification-orchestrator"
                                + "&permissions=users:display-names:read"))
                .andExpect(status().isBadRequest());
    }
}
