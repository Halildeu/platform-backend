package com.example.kcsmsotp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.AuthenticatorConfigModel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Contract test for the two-leg send chain. The stubs pin the EXACT request
 * shapes measured against auth-service (ServiceTokenController: Basic + form,
 * access_token JSON) and notification-orchestrator (internal intent endpoint:
 * Bearer + camelCase SubmitIntentRequest, 202) — if either side's contract
 * drifts, this test is the tripwire that says the SPI must follow.
 */
class NotifySmsGatewayTest {

    private WireMockServer server;
    private NotifySmsGateway gateway;

    private static final String TOKEN_PATH = "/oauth2/token";
    private static final String INTENT_PATH = "/api/v1/internal/notify/intents";

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();

        AuthenticatorConfigModel model = new AuthenticatorConfigModel();
        model.setConfig(Map.of(
                SmsOtpConfig.CFG_TOKEN_URL, server.baseUrl() + TOKEN_PATH,
                SmsOtpConfig.CFG_INTENT_URL, server.baseUrl() + INTENT_PATH));
        Function<String, String> env =
                key -> SmsOtpConfig.SECRET_ENV.equals(key) ? "test-secret" : null;
        SmsOtpConfig cfg = SmsOtpConfig.from(model, env);
        assertThat(cfg.isSendable()).isTrue();

        gateway = new NotifySmsGateway(HttpClient.newHttpClient(), new ObjectMapper(), cfg);
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private void stubHappyMint() {
        server.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":60}")));
    }

    private void stubHappyIntent() {
        server.stubFor(post(urlEqualTo(INTENT_PATH)).willReturn(aResponse()
                .withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"intentId\":\"x\",\"status\":\"accepted\"}")));
    }

    @Test
    void send_happyPath_mintsWithBasicAndForm_thenSubmitsCamelCaseIntentWithBearer()
            throws Exception {
        stubHappyMint();
        stubHappyIntent();

        gateway.send("+905321234567", "tr", "123456", "sms-otp-root-1-0");

        String expectedBasic = "Basic " + Base64.getEncoder().encodeToString(
                "keycloak-sms-otp:test-secret".getBytes(StandardCharsets.UTF_8));
        server.verify(exactly(1), postRequestedFor(urlEqualTo(TOKEN_PATH))
                .withHeader("Authorization", equalTo(expectedBasic))
                .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withRequestBody(containing("audience=notification-orchestrator"))
                .withRequestBody(containing("permissions=notify%3Aintents%3Asystem")));

        server.verify(exactly(1), postRequestedFor(urlEqualTo(INTENT_PATH))
                .withHeader("Authorization", equalTo("Bearer tok-1"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.intentId"))
                .withRequestBody(matchingJsonPath("$.idempotencyKey", equalTo("sms-otp-root-1-0")))
                .withRequestBody(matchingJsonPath("$.orgId", equalTo("platform-system")))
                .withRequestBody(matchingJsonPath("$.topicKey", equalTo("auth.mfa.sms-otp")))
                .withRequestBody(matchingJsonPath("$.severity", equalTo("info")))
                .withRequestBody(matchingJsonPath("$.dataClassification", equalTo("security")))
                .withRequestBody(matchingJsonPath("$.recipients[0].type", equalTo("external")))
                .withRequestBody(matchingJsonPath("$.recipients[0].phone", equalTo("+905321234567")))
                .withRequestBody(matchingJsonPath("$.recipients[0].locale", equalTo("tr")))
                .withRequestBody(matchingJsonPath("$.template.templateId", equalTo("auth.sms-otp")))
                .withRequestBody(matchingJsonPath("$.template.locale", equalTo("tr")))
                .withRequestBody(matchingJsonPath("$.channels[0]", equalTo("sms")))
                .withRequestBody(matchingJsonPath("$.payload.code", equalTo("123456"))));
    }

    @Test
    void send_mintRejected_throwsWithoutTouchingTheIntentEndpoint() {
        server.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(401)));
        stubHappyIntent();

        assertThatThrownBy(() -> gateway.send("+905321234567", "tr", "123456", "k"))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("mint failed: HTTP 401");

        server.verify(exactly(0), postRequestedFor(urlEqualTo(INTENT_PATH)));
    }

    @Test
    void send_emptyAccessToken_throws() {
        server.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"token_type\":\"Bearer\"}")));

        assertThatThrownBy(() -> gateway.send("+905321234567", "tr", "123456", "k"))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("empty access_token");
    }

    @Test
    void send_intentRejected_throws_andDoesNotRetry() {
        stubHappyMint();
        server.stubFor(post(urlEqualTo(INTENT_PATH)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> gateway.send("+905321234567", "tr", "123456", "k"))
                .isInstanceOf(SmsSendException.class)
                .hasMessageContaining("intent failed: HTTP 500");

        // No client-side retry loop: the user-visible resend button is the retry.
        server.verify(exactly(1), postRequestedFor(urlEqualTo(INTENT_PATH)));
    }
}
