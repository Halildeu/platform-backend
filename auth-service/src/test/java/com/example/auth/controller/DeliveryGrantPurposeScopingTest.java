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

/**
 * Purpose scoping (gitops#3285).
 *
 * <p>Adding a second purpose to the flat allow-lists would have looked like a
 * feature and been a hole: the lists would no longer say WHICH purpose may use
 * what, so the invitation sender could mint an MFA grant and the MFA client
 * could mint an invitation. These tests are the construction that replaces that
 * convention — each one asserts a door that must stay shut.
 *
 * <p>Two clients, two purposes, deliberately crossed in the config:
 * {@code keycloak-sms-otp} owns {@code mfa_otp}, {@code user-service} owns
 * {@code account_invite}, and neither is listed in the other.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "security.service-clients.clients.keycloak-sms-otp.secret=kc-sms-secret",
        "security.service-clients.clients.keycloak-sms-otp.allowed-audiences[0]=notification-orchestrator",
        "security.service-clients.clients.keycloak-sms-otp.allowed-permissions[0]=notify:intents:system",
        "security.service-clients.clients.user-service.secret=user-secret",
        "security.service-clients.clients.user-service.allowed-audiences[0]=notification-orchestrator",
        "security.service-clients.clients.user-service.allowed-permissions[0]=notify:intents:system",

        "security.mfa-delivery-grant.purposes.mfa_otp.allowed-clients[0]=keycloak-sms-otp",
        "security.mfa-delivery-grant.purposes.account_invite.allowed-clients[0]=user-service",
        "security.mfa-delivery-grant.ttl-seconds=120",

        "auth.impersonation.keycloak-token-url=http://localhost:9999/token",
        "auth.impersonation.keycloak-broker-url=http://localhost:9999/broker",
        "spring.datasource.url=jdbc:h2:mem:grantpurposedb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
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
class DeliveryGrantPurposeScopingTest {

    @Autowired
    private MockMvc mockMvc;

    private static String basic(String clientId, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private org.springframework.test.web.servlet.ResultActions ask(
            String clientId, String secret, String purpose,
            String recipient, String channel, String topic, String template) throws Exception {
        var req = post("/oauth2/mfa-delivery-grant")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Authorization", basic(clientId, secret))
                .param("audience", "notification-orchestrator")
                .param("subject", "subject-1")
                .param("recipient", recipient)
                .param("channel", channel)
                .param("topic", topic)
                .param("template", template)
                .param("auth_session_id", "session-1");
        if (purpose != null) {
            req = req.param("purpose", purpose);
        }
        return mockMvc.perform(req);
    }

    // ── each purpose works for its own client ────────────────────────────

    @Test
    void mfaClientGetsAnMfaGrant() throws Exception {
        ask("keycloak-sms-otp", "kc-sms-secret", "mfa_otp",
                "+905551112233", "sms", "auth.mfa.sms-otp", "auth.sms-otp")
                .andExpect(status().isOk());
    }

    @Test
    void inviteClientGetsAnInviteGrant() throws Exception {
        ask("user-service", "user-secret", "account_invite",
                "someone@acik.com", "email", "auth.admin-invite", "auth.admin-invite")
                .andExpect(status().isOk());
    }

    // ── and neither can borrow the other's ───────────────────────────────

    /** The whole reason the lists moved inside a purpose. */
    @Test
    void inviteClientCannotMintAnMfaGrant() throws Exception {
        ask("user-service", "user-secret", "mfa_otp",
                "+905551112233", "sms", "auth.mfa.sms-otp", "auth.sms-otp")
                .andExpect(status().isForbidden());
    }

    @Test
    void mfaClientCannotMintAnInviteGrant() throws Exception {
        ask("keycloak-sms-otp", "kc-sms-secret", "account_invite",
                "someone@acik.com", "email", "auth.admin-invite", "auth.admin-invite")
                .andExpect(status().isForbidden());
    }

    /**
     * Even holding the right purpose, a caller cannot reach outside that
     * purpose's topics — otherwise the client list would be the only guard and
     * one compromised client would reach every template.
     */
    @Test
    void aPurposeCannotReachAnotherPurposesTopic() throws Exception {
        ask("user-service", "user-secret", "account_invite",
                "someone@acik.com", "email", "auth.mfa.email-otp", "auth.admin-invite")
                .andExpect(status().isBadRequest());
    }

    @Test
    void aPurposeCannotReachAnotherPurposesTemplate() throws Exception {
        ask("user-service", "user-secret", "account_invite",
                "someone@acik.com", "email", "auth.admin-invite", "auth.email-otp")
                .andExpect(status().isBadRequest());
    }

    /** The invitation lane is e-mail only: nobody has a phone before they exist. */
    @Test
    void theInviteLaneRefusesSms() throws Exception {
        ask("user-service", "user-secret", "account_invite",
                "+905551112233", "sms", "auth.admin-invite", "auth.admin-invite")
                .andExpect(status().isBadRequest());
    }

    // ── unknown and absent purposes ──────────────────────────────────────

    /** A typo must not inherit another lane's permissions. */
    @Test
    void anUnknownPurposeIsRefusedRatherThanDefaulted() throws Exception {
        ask("keycloak-sms-otp", "kc-sms-secret", "password_reset",
                "+905551112233", "sms", "auth.mfa.sms-otp", "auth.sms-otp")
                .andExpect(status().isBadRequest());
    }

    /** Callers that predate the parameter keep the lane they always had. */
    @Test
    void anAbsentPurposeMeansMfa() throws Exception {
        ask("keycloak-sms-otp", "kc-sms-secret", null,
                "+905551112233", "sms", "auth.mfa.sms-otp", "auth.sms-otp")
                .andExpect(status().isOk());
    }

    /** …and that default is still scoped: it does not let the invite client in. */
    @Test
    void anAbsentPurposeDoesNotAdmitTheInviteClient() throws Exception {
        ask("user-service", "user-secret", null,
                "+905551112233", "sms", "auth.mfa.sms-otp", "auth.sms-otp")
                .andExpect(status().isForbidden());
    }
}
