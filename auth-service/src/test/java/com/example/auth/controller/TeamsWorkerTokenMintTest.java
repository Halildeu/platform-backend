package com.example.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.auth.serviceauth.ServiceClientsProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Worker mint is scoped to one audience and explicit permission; an unprovisioned secret stays disabled. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "security.service-clients.clients.teams-capture-worker.secret=synthetic-worker-secret",
        "security.service-clients.clients.teams-capture-worker.allowed-audiences[0]=meeting-service",
        "security.service-clients.clients.teams-capture-worker.allowed-permissions[0]=meeting:teams-schedule:authorize",
        "security.service-clients.clients.teams-capture-worker.require-explicit-permissions=true",
        "security.service-mint.allowed-audiences=meeting-service",
        "security.service-mint.allowed-permissions=meeting:teams-schedule:authorize",
        "security.service-mint.rate-limit-per-minute=100",
        "security.service-mint.failed-auth-rate-limit-per-minute=1000",
        "auth.impersonation.keycloak-token-url=http://localhost:9999/token",
        "auth.impersonation.keycloak-broker-url=http://localhost:9999/broker",
        "spring.datasource.url=jdbc:h2:mem:teamsworkermint;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
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
class TeamsWorkerTokenMintTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceClientsProperties serviceClients;

    @Test void exactWorkerPermissionMintsForMeetingServiceOnly() throws Exception {
        var response = mockMvc.perform(post("/oauth2/token").header("Authorization", basic("teams-capture-worker", "synthetic-worker-secret"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("grant_type=client_credentials&audience=meeting-service&permissions=meeting:teams-schedule:authorize"))
                .andExpect(status().isOk()).andReturn();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getResponse().getContentAsString());
        var claims = com.nimbusds.jwt.SignedJWT.parse(json.get("access_token").asText()).getJWTClaimsSet();
        assertThat(claims.getAudience()).containsExactly("meeting-service");
        assertThat(claims.getSubject()).isEqualTo("teams-capture-worker");
        assertThat(claims.getStringClaim("client_id")).isEqualTo("teams-capture-worker");
        assertThat(claims.getStringListClaim("perm")).containsExactly("meeting:teams-schedule:authorize");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "audience=user-service&permissions=meeting:teams-schedule:authorize",
        "audience=meeting-service&permissions=meeting:analysis-result:write",
        "audience=meeting-service"})
    void otherAudiencePermissionOrImplicitGrantCannotMint(String form) throws Exception {
        mockMvc.perform(post("/oauth2/token").header("Authorization", basic("teams-capture-worker", "synthetic-worker-secret"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("grant_type=client_credentials&" + form))
                .andExpect(status().is4xxClientError());
    }
    @Test void unprovisionedWorkerSecretCannotMint() throws Exception {
        var registration = serviceClients.getClients().get("teams-capture-worker");
        String previous = registration.getSecret(); registration.setSecret("");
        try {
            mockMvc.perform(post("/oauth2/token").header("Authorization", basic("teams-capture-worker", "synthetic-worker-secret"))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .content("grant_type=client_credentials&audience=meeting-service&permissions=meeting:teams-schedule:authorize"))
                    .andExpect(status().isUnauthorized());
        } finally { registration.setSecret(previous); }
    }

    private static String basic(String clientId, String secret) {
        String raw = clientId + ":" + secret;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
