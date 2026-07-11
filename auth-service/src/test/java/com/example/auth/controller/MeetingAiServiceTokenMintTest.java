package com.example.auth.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.auth.serviceauth.ServiceClientsProperties;
import com.example.auth.serviceauth.ServiceMintPolicyProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
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
 * {@code meeting:analysis-result:write} — AND that this authority is bound to meeting-ai
 * alone, not handed to every authenticated service-client.
 *
 * <p>The mint allow-list makes the audience/permission mintable <em>at all</em>; the
 * per-permission client binding makes {@code meeting:analysis-result:write} mintable
 * <strong>only</strong> by meeting-ai. Without that binding any valid client could mint a
 * meeting-service write token and forge analysis results, breaking meeting-service's
 * single-writer authority (Verdict A). These tests pin both the positive path and the
 * three denials Codex flagged: cross-client, blank-presented-secret, blank-configured
 * secret (unprovisioned client).
 *
 * <p>Deliberately its own context (not folded into {@link ServiceTokenControllerTest}) so
 * it neither depends on nor is broken by that suite's pre-existing rate-limit=1 /
 * impersonation-placeholder fragility (tracked separately). The mint policy + client map
 * are supplied as {@code @Primary} test beans so the binding is deterministic rather than
 * reconstructed from property strings.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "security.service-mint.allowed-audiences=meeting-service",
        "security.service-mint.allowed-permissions=meeting:analysis-result:write",
        // NOTE: the permission-client binding is injected via @BeforeEach below rather
        // than an inline property. Spring's relaxed binding cannot parse a
        // Map<String,Set<String>> whose key contains ':' from a flat property string
        // (both "[key]=v" and "[key][0]=v" silently no-op). The production YAML form
        // (application-k8s.yml, "[meeting:analysis-result:write]:" + list) binds fine and
        // is covered separately; here we set the same value on the shared singleton.
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

    @Autowired
    private ServiceMintPolicyProperties mintPolicy;

    @BeforeEach
    void bindMeetingWriteScopeToMeetingAi() {
        // The controller reads this same singleton per request, so setting it here is
        // sufficient. Binds meeting:analysis-result:write to meeting-ai only.
        mintPolicy.setPermissionClientBindings(
                Map.of("meeting:analysis-result:write", Set.of("meeting-ai")));
    }

    private static String basic(String clientId, String secret) {
        String raw = clientId + ":" + secret;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @TestConfiguration
    static class MintTestConfig {
        @Bean
        @Primary
        ServiceClientsProperties testClients() {
            ServiceClientsProperties p = new ServiceClientsProperties();
            Map<String, String> m = new HashMap<>();
            m.put("meeting-ai", "test-secret");
            // A *valid, authenticated* client that is deliberately NOT bound to the
            // meeting write scope — used to prove cross-client denial.
            m.put("other-service", "test-secret-2");
            // A client whose secret has not been provisioned yet (ESO/Vault pending):
            // its configured secret is blank, so it must stay fully disabled.
            m.put("unprovisioned-ai", "");
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

    /**
     * Codex #2: a different, fully authenticated client may pass the global audience +
     * permission allow-list, yet must still be refused the bound write scope. This is the
     * escalation the binding closes: audience+permission alone are not sufficient.
     */
    @Test
    void crossClient_validButUnbound_cannotMintMeetingWriteScope() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("other-service", "test-secret-2"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=meeting-service"
                                + "&permissions=meeting:analysis-result:write"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Codex #3: an empty presented secret must never authenticate. Before this fix
     * {@code "".equals("")} against a blank-default client secret let anyone mint as that
     * client.
     */
    @Test
    void blankPresentedSecret_isRejected() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("meeting-ai", ""))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=meeting-service"
                                + "&permissions=meeting:analysis-result:write"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Codex #3 (config side): a client whose *configured* secret is still blank (Vault seed
     * pending) stays disabled even when a non-blank secret is presented — the blank default
     * genuinely deactivates the client rather than accidentally accepting a blank match.
     */
    @Test
    void unprovisionedClient_blankConfiguredSecret_isDisabled() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("unprovisioned-ai", "any-guess"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=meeting-service"
                                + "&permissions=meeting:analysis-result:write"))
                .andExpect(status().isUnauthorized());
    }
}
