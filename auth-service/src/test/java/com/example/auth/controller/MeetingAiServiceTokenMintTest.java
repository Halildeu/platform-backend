package com.example.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * ai#244 AI-1: proves auth-service can mint the service token meeting-ai needs to push
 * analysis results — {@code audience=meeting-service} carrying
 * {@code meeting:analysis-result:write} — AND that this authority is bound to meeting-ai
 * alone, not handed to every authenticated service client.
 *
 * <p>The global mint allow-list is an upper ceiling. Each authenticated client also has
 * explicit audience and permission ceilings, so authority cannot leak between registered
 * clients. These tests pin the positive path, cross-client denial, client-bound token
 * identity/cache behavior, and both blank-secret denial paths.
 *
 * <p>Deliberately its own context (not folded into {@link ServiceTokenControllerTest}) so
 * it neither depends on nor is broken by that suite's independent rate-limit window.
 * Real Spring property binding
 * supplies the same nested client-registration structure used by production YAML.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "security.service-clients.clients.meeting-ai.secret=test-secret",
        "security.service-clients.clients.meeting-ai.allowed-audiences[0]=meeting-service",
        "security.service-clients.clients.meeting-ai.allowed-audiences[1]=transcript-service",
        "security.service-clients.clients.meeting-ai.allowed-permissions[0]=meeting:analysis-result:write",
        "security.service-clients.clients.meeting-ai.allowed-permissions[1]=transcript:canonical:read",
        "security.service-clients.clients.meeting-ai.allowed-permissions-by-audience[meeting-service][0]=meeting:analysis-result:write",
        "security.service-clients.clients.meeting-ai.allowed-permissions-by-audience[transcript-service][0]=transcript:canonical:read",
        "security.service-clients.clients.meeting-ai.require-explicit-permissions=true",
        "security.service-clients.clients.transcript-service.secret=transcript-secret",
        "security.service-clients.clients.transcript-service.allowed-audiences[0]=meeting-service",
        "security.service-clients.clients.transcript-service.allowed-permissions[0]=meeting:session:resolve",
        "security.service-clients.clients.transcript-service.require-explicit-permissions=true",
        "security.service-clients.clients.other-service.secret=test-secret-2",
        "security.service-clients.clients.other-service.allowed-audiences[0]=meeting-service",
        "security.service-clients.clients.other-service.allowed-permissions[0]=permissions:read",
        "security.service-clients.clients.other-service.require-explicit-permissions=true",
        "security.service-clients.clients.meeting-ai-canary.secret=canary-secret",
        "security.service-clients.clients.meeting-ai-canary.allowed-audiences[0]=meeting-service",
        "security.service-clients.clients.meeting-ai-canary.allowed-permissions[0]=meeting:analysis-result:write",
        "security.service-clients.clients.meeting-ai-canary.require-explicit-permissions=true",
        "security.service-clients.clients.unprovisioned-ai.secret=",
        "security.service-clients.clients.unprovisioned-ai.allowed-audiences[0]=meeting-service",
        "security.service-clients.clients.unprovisioned-ai.allowed-permissions[0]=meeting:analysis-result:write",
        "security.service-mint.allowed-audiences=meeting-service,transcript-service",
        "security.service-mint.allowed-permissions=meeting:analysis-result:write,meeting:session:resolve,transcript:canonical:read,permissions:read",
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
    private ObjectMapper objectMapper;

    private static String basic(String clientId, String secret) {
        String raw = clientId + ":" + secret;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void meetingAi_mints_meetingService_analysisResultWrite() throws Exception {
        MvcResult result = mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("meeting-ai", "test-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=meeting-service"
                                + "&permissions=meeting:analysis-result:write"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andReturn();

        JWTClaimsSet claims = claims(result);
        assertEquals("meeting-ai", claims.getSubject());
        assertEquals("meeting-ai", claims.getStringClaim("client_id"));
        assertEquals("meeting-ai", claims.getStringClaim("svc"));
        assertEquals(java.util.List.of("meeting-service"), claims.getAudience());
        assertEquals(java.util.List.of("meeting:analysis-result:write"), claims.getStringListClaim("perm"));
    }

    @Test
    void meetingAi_mintsOnlyCanonicalReadForTranscriptService() throws Exception {
        MvcResult result = mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("meeting-ai", "test-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=transcript-service"
                                + "&permissions=transcript:canonical:read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andReturn();

        JWTClaimsSet claims = claims(result);
        assertEquals("meeting-ai", claims.getSubject());
        assertEquals(java.util.List.of("transcript-service"), claims.getAudience());
        assertEquals(java.util.List.of("transcript:canonical:read"), claims.getStringListClaim("perm"));

        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("meeting-ai", "test-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=transcript-service"
                                + "&permissions=meeting:analysis-result:write"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void transcriptServiceMintsOnlySessionResolvePermission() throws Exception {
        MvcResult result = mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("transcript-service", "transcript-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=meeting-service"
                                + "&permissions=meeting:session:resolve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andReturn();

        JWTClaimsSet claims = claims(result);
        assertEquals("transcript-service", claims.getSubject());
        assertEquals(java.util.List.of("meeting-service"), claims.getAudience());
        assertEquals(java.util.List.of("meeting:session:resolve"), claims.getStringListClaim("perm"));
    }

    @Test
    void servicePermissionsRemainClientLocalAcrossSharedAudience() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("transcript-service", "transcript-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=meeting-service"
                                + "&permissions=meeting:analysis-result:write"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("meeting-ai", "test-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=meeting-service"
                                + "&permissions=meeting:session:resolve"))
                .andExpect(status().isBadRequest());
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

    @Test
    void crossClient_validButUnbound_cannotMintMeetingWriteScope() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("other-service", "test-secret-2"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=meeting-service"
                                + "&permissions=meeting:analysis-result:write"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void meetingAi_missingExplicitPermission_isRejected() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("meeting-ai", "test-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=meeting-service"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sameAudienceAndPermission_tokensAreSeparatedByClientIdentity() throws Exception {
        MvcResult primary = mint("meeting-ai", "test-secret");
        MvcResult canary = mint("meeting-ai-canary", "canary-secret");

        String primaryToken = token(primary);
        String canaryToken = token(canary);
        assertNotEquals(primaryToken, canaryToken);
        assertEquals("meeting-ai", SignedJWT.parse(primaryToken).getJWTClaimsSet().getSubject());
        assertEquals("meeting-ai-canary", SignedJWT.parse(canaryToken).getJWTClaimsSet().getSubject());
    }

    @Test
    void blankPresentedSecret_isRejected() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("meeting-ai", ""))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=meeting-service"
                                + "&permissions=meeting:analysis-result:write"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unprovisionedClient_blankConfiguredSecret_isDisabled() throws Exception {
        mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic("unprovisioned-ai", "any-guess"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=meeting-service"
                                + "&permissions=meeting:analysis-result:write"))
                .andExpect(status().isUnauthorized());
    }

    private MvcResult mint(String clientId, String secret) throws Exception {
        return mockMvc.perform(post("/oauth2/token")
                        .header("Authorization", basic(clientId, secret))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&audience=meeting-service"
                                + "&permissions=meeting:analysis-result:write"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private JWTClaimsSet claims(MvcResult result) throws Exception {
        return SignedJWT.parse(token(result)).getJWTClaimsSet();
    }

    private String token(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("access_token")
                .asText();
    }
}
