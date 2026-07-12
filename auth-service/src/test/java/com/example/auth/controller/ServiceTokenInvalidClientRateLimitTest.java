package com.example.auth.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "security.service-clients.clients.known-client.secret=correct-secret",
        "security.service-mint.failed-auth-rate-limit-per-minute=2",
        "auth.impersonation.keycloak-token-url=http://localhost:9999/token",
        "auth.impersonation.keycloak-broker-url=http://localhost:9999/broker",
        "spring.datasource.url=jdbc:h2:mem:invalidclientratelimit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
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
class ServiceTokenInvalidClientRateLimitTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void invalidCredentials_shareBoundedBudgetAndAreRateLimited() throws Exception {
        expectInvalidClient("known-client", "wrong-secret");
        expectInvalidClient("unknown-client", "wrong-secret");

        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("another-unknown-client", "wrong-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=permission-service"))
                .andExpect(status().isTooManyRequests());
    }

    private void expectInvalidClient(String clientId, String secret) throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic(clientId, secret))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=permission-service"))
                .andExpect(status().isUnauthorized());
    }

    private String basic(String clientId, String secret) {
        String raw = clientId + ":" + secret;
        return "Basic " + Base64.getEncoder()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
