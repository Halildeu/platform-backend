package com.example.auth.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

/**
 * The grant endpoint is a capability issuer, so its NEGATIVE surface is the
 * point: every field it attests must be pinned by policy, and a caller that
 * is merely a valid service client must not be able to obtain one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "security.service-clients.clients.keycloak-sms-otp.secret=kc-sms-secret",
        "security.service-clients.clients.keycloak-sms-otp.allowed-audiences[0]=notification-orchestrator",
        "security.service-clients.clients.keycloak-sms-otp.allowed-permissions[0]=notify:intents:system",
        "security.service-clients.clients.keycloak-sms-otp.require-explicit-permissions=true",
        // A perfectly valid service client that is NOT on the grant allow-list.
        "security.service-clients.clients.user-service.secret=user-secret",
        "security.service-clients.clients.user-service.allowed-audiences[0]=notification-orchestrator",
        "security.service-clients.clients.user-service.allowed-permissions[0]=notify:intents:system",
        "security.mfa-delivery-grant.allowed-clients[0]=keycloak-sms-otp",
        "security.mfa-delivery-grant.ttl-seconds=120",
        "auth.impersonation.keycloak-token-url=http://localhost:9999/token",
        "auth.impersonation.keycloak-broker-url=http://localhost:9999/broker",
        "spring.datasource.url=jdbc:h2:mem:mfagrantdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
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
class MfaDeliveryGrantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private String basic(String clientId, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Builds a full request. Overrides are passed in rather than chained with
     * a second .param(): MockMvc APPENDS repeated values and the controller
     * reads the FIRST one, so a chained override silently sends the valid
     * value and the negative test passes for the wrong reason.
     */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder grantRequest(
            String clientId, String secret, String recipient, String channel,
            String topic, String template) {
        return post("/oauth2/mfa-delivery-grant")
                .header("Authorization", basic(clientId, secret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("audience", "notification-orchestrator")
                .param("subject", "3520324b-aaaa-bbbb-cccc-000000000001")
                .param("recipient", recipient)
                .param("channel", channel)
                .param("topic", topic)
                .param("template", template)
                .param("auth_session_id", "session-1");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder grantRequest(
            String clientId, String secret) {
        return grantRequest(clientId, secret, "+905321234567", "sms",
                "auth.mfa.sms-otp", "auth.sms-otp");
    }

    @Test
    void allowListedClient_getsAGrant() throws Exception {
        mockMvc.perform(grantRequest("keycloak-sms-otp", "kc-sms-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grant").isNotEmpty())
                .andExpect(jsonPath("$.expires_in").value(120));
    }

    @Test
    void validServiceClientNotOnTheGrantAllowList_isRefused() throws Exception {
        // The whole point of the allow-list: being able to submit intents does
        // NOT imply being able to authorise a delivery to an arbitrary number.
        mockMvc.perform(grantRequest("user-service", "user-secret"))
                .andExpect(status().isForbidden());
    }

    @Test
    void wrongSecret_isUnauthorized() throws Exception {
        mockMvc.perform(grantRequest("keycloak-sms-otp", "nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unlistedTemplate_isRefused() throws Exception {
        mockMvc.perform(grantRequest("keycloak-sms-otp", "kc-sms-secret", "+905321234567", "sms", "auth.mfa.sms-otp", "marketing.blast"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unlistedTopic_isRefused() throws Exception {
        mockMvc.perform(grantRequest("keycloak-sms-otp", "kc-sms-secret", "+905321234567", "sms", "marketing.campaign", "auth.sms-otp"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unlistedChannel_isRefused() throws Exception {
        // Was "email" until gitops#3230 made e-mail a real second factor. The
        // assertion is about an UNLISTED channel, so it needs one that
        // genuinely is not on the list.
        mockMvc.perform(grantRequest("keycloak-sms-otp", "kc-sms-secret", "+905321234567", "push", "auth.mfa.sms-otp", "auth.sms-otp"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emailChannel_acceptsAnAddressAndRefusesAPhoneNumber() throws Exception {
        // The recipient shape follows the channel. Pinning E.164 for every
        // channel would refuse every e-mail grant before the lane started.
        mockMvc.perform(grantRequest("keycloak-sms-otp", "kc-sms-secret", "ops@acik.com", "email", "auth.mfa.email-otp", "auth.email-otp"))
                .andExpect(status().isOk());

        mockMvc.perform(grantRequest("keycloak-sms-otp", "kc-sms-secret", "+905321234567", "email", "auth.mfa.email-otp", "auth.email-otp"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void smsChannel_stillRefusesAnAddress() throws Exception {
        mockMvc.perform(grantRequest("keycloak-sms-otp", "kc-sms-secret", "ops@acik.com", "sms", "auth.mfa.sms-otp", "auth.sms-otp"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonE164Recipient_isRefused() throws Exception {
        mockMvc.perform(grantRequest("keycloak-sms-otp", "kc-sms-secret", "05321234567", "sms", "auth.mfa.sms-otp", "auth.sms-otp"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingAuthSessionId_isRefused() throws Exception {
        mockMvc.perform(post("/oauth2/mfa-delivery-grant")
                        .header("Authorization", basic("keycloak-sms-otp", "kc-sms-secret"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("audience", "notification-orchestrator")
                        .param("subject", "u1")
                        .param("recipient", "+905321234567")
                        .param("channel", "sms")
                        .param("topic", "auth.mfa.sms-otp")
                        .param("template", "auth.sms-otp"))
                .andExpect(status().isBadRequest());
    }
}
