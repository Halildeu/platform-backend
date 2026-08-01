package com.example.user.serviceauth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * The invitation e-mail had never been delivered because the intent carried no
 * delivery grant (gitops#3285). These pin the shape of the request that fixes
 * it, and the behaviour when it cannot be obtained.
 */
class DeliveryGrantClientTest {

    private WireMockServer server;
    private ServiceTokenClientProperties props;
    private DeliveryGrantClient client;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        props = new ServiceTokenClientProperties();
        props.setTokenUrl("http://localhost:" + server.port() + "/oauth2/token");
        props.setClientId("user-service");
        props.setClientSecret("user-secret");
        client = new DeliveryGrantClient(props, WebClient.builder());
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private String mint() {
        return client.mintForInvite("notification-orchestrator", "42",
                "admin@acik.com", "auth.admin-invite", "auth.admin-invite");
    }

    @Test
    void asksTheGrantEndpointForTheInvitePurposeOnly() {
        server.stubFor(post(urlPathEqualTo("/oauth2/mfa-delivery-grant"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"grant\":\"signed.jwt.value\",\"expires_in\":120}")));

        assertThat(mint()).isEqualTo("signed.jwt.value");

        // The token path must NOT be what got called — deriving the grant URL
        // from it is the one place that could quietly aim at the wrong endpoint.
        server.verify(postRequestedFor(urlPathEqualTo("/oauth2/mfa-delivery-grant"))
                .withRequestBody(containing("purpose=account_invite"))
                .withRequestBody(containing("channel=email"))
                .withRequestBody(containing("topic=auth.admin-invite")));
    }

    @Test
    void authenticatesWithTheServiceClientCredentials() {
        server.stubFor(post(urlPathEqualTo("/oauth2/mfa-delivery-grant"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"grant\":\"g\"}")));

        mint();

        String basic = "Basic " + java.util.Base64.getEncoder().encodeToString(
                "user-service:user-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        server.verify(postRequestedFor(urlPathEqualTo("/oauth2/mfa-delivery-grant"))
                .withHeader("Authorization", equalTo(basic)));
    }

    /**
     * A refused mint must not throw into the invitation path. It returns null,
     * the intent goes without a grant, and notify refuses it the way it always
     * did — no worse than before, and never a failed user creation.
     */
    @Test
    void aRefusedMintYieldsNullRatherThanThrowing() {
        server.stubFor(post(urlPathEqualTo("/oauth2/mfa-delivery-grant"))
                .willReturn(aResponse().withStatus(403)));

        assertThat(mint()).isNull();
    }

    @Test
    void aResponseWithoutAGrantYieldsNull() {
        server.stubFor(post(urlPathEqualTo("/oauth2/mfa-delivery-grant"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"expires_in\":120}")));

        assertThat(mint()).isNull();
    }

    /** With the mint client switched off nothing is attempted at all. */
    @Test
    void aDisabledClientDoesNotCallOut() {
        props.setEnabled(false);

        assertThat(mint()).isNull();
        server.verify(0, postRequestedFor(urlPathEqualTo("/oauth2/mfa-delivery-grant")));
    }
}
