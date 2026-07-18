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

/** Pins the transcript-service registration itself as disabled when its configured secret is blank. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "security.service-clients.clients.transcript-service.secret=",
        "security.service-clients.clients.transcript-service.allowed-audiences[0]=meeting-service",
        "security.service-clients.clients.transcript-service.allowed-permissions[0]=meeting:session:resolve",
        "security.service-clients.clients.transcript-service.require-explicit-permissions=true",
        "security.service-mint.allowed-audiences=meeting-service",
        "security.service-mint.allowed-permissions=meeting:session:resolve",
        "security.service-mint.rate-limit-per-minute=100",
        "security.service-mint.failed-auth-rate-limit-per-minute=1000",
        "auth.impersonation.keycloak-token-url=http://localhost:9999/token",
        "auth.impersonation.keycloak-broker-url=http://localhost:9999/broker",
        "spring.datasource.url=jdbc:h2:mem:transcriptblanksecret;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
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
class TranscriptServiceBlankSecretTokenMintTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceClientsProperties serviceClients;

    @Test
    void transcriptService_blankConfiguredSecret_isDisabled() throws Exception {
        assertThat(serviceClients.getClients().get("transcript-service").getSecret()).isBlank();

        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("transcript-service", "any-guess"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=meeting-service"
                                + "&permissions=meeting:session:resolve"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    private static String basic(String clientId, String secret) {
        String raw = clientId + ":" + secret;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
