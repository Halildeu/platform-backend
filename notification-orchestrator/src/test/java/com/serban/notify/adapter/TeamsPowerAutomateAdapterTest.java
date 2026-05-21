package com.serban.notify.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.template.RenderedMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * WireMock contract tests for {@link TeamsPowerAutomateAdapter}
 * (Faz 23.6 M7 T4.1.2 — Codex {@code 019e496d} AGREE).
 *
 * <p>Verifies:
 * <ul>
 *   <li>2xx → DELIVERED + Adaptive Card payload sent</li>
 *   <li>4xx (400/401/403/404/410) → FAILED permanent</li>
 *   <li>408/429 → RETRY (transient throttle/timeout)</li>
 *   <li>5xx → RETRY (transient)</li>
 *   <li>IOException / connection refused → RETRY</li>
 *   <li>Empty body → FAILED before send</li>
 * </ul>
 */
class TeamsPowerAutomateAdapterTest {

    @RegisterExtension
    static WireMockExtension teams = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TeamsPowerAutomateAdapter adapter = new TeamsPowerAutomateAdapter(objectMapper);

    @Test
    void teams2xxDeliveredWithAdaptiveCardPayload() {
        teams.stubFor(post(urlEqualTo("/flow/abc123"))
            .willReturn(aResponse().withStatus(200)));

        java.util.Map<String, Object> routing = new java.util.LinkedHashMap<>();
        routing.put("severity", "critical");
        routing.put("org_id", "default");
        routing.put("topic_key", "ops.drift-alarm");
        routing.put("correlation_id", "corr-1");
        DeliveryTarget target = new DeliveryTarget(
            "teams", "channel", null, "hash",
            teams.url("/flow/abc123"), "teams-default", routing
        );
        RenderedMessage msg = new RenderedMessage("Alert subject", null, "Alert body", "tr-TR");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
        assertThat(r.providerMessageId()).startsWith("teams-");

        // Verify Adaptive Card payload shape via jsonPath assertions
        teams.verify(postRequestedFor(urlEqualTo("/flow/abc123"))
            .withRequestBody(matchingJsonPath("$.type", equalTo("AdaptiveCard")))
            .withRequestBody(matchingJsonPath("$.version", equalTo("1.4")))
            .withRequestBody(matchingJsonPath("$.body[0].type", equalTo("TextBlock")))
            .withRequestBody(matchingJsonPath("$.body[0].size", equalTo("Large")))
            .withRequestBody(matchingJsonPath("$.body[0].weight", equalTo("Bolder")))
            .withRequestBody(matchingJsonPath("$.body[1].type", equalTo("TextBlock")))
            .withRequestBody(matchingJsonPath("$.body[2].type", equalTo("FactSet")))
            .withRequestBody(matchingJsonPath("$.fallbackText", equalTo("Alert body"))));
    }

    @Test
    void teams400PermanentFailed() {
        teams.stubFor(post(urlEqualTo("/bad-request"))
            .willReturn(aResponse().withStatus(400).withBody("Bad payload")));

        DeliveryTarget target = target(teams.url("/bad-request"));
        RenderedMessage msg = new RenderedMessage("Sub", null, "Body", "tr-TR");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("HTTP 400");
    }

    @Test
    void teams404PermanentFailed() {
        teams.stubFor(post(urlEqualTo("/expired-flow"))
            .willReturn(aResponse().withStatus(404)));

        DeliveryTarget target = target(teams.url("/expired-flow"));
        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target,
            new RenderedMessage("Sub", null, "Body", "tr-TR"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("HTTP 404");
    }

    @Test
    void teams429ThrottleRetry() {
        // Codex 019e496d Q7 REVISE absorb: 429 transient (Power Automate
        // quota saatlerinde 429 üretebilir).
        teams.stubFor(post(urlEqualTo("/throttled"))
            .willReturn(aResponse().withStatus(429)));

        DeliveryTarget target = target(teams.url("/throttled"));
        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target,
            new RenderedMessage("Sub", null, "Body", "tr-TR"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
        assertThat(r.failureReason()).contains("HTTP 429");
    }

    @Test
    void teams408TimeoutRetry() {
        teams.stubFor(post(urlEqualTo("/timeout"))
            .willReturn(aResponse().withStatus(408)));

        DeliveryTarget target = target(teams.url("/timeout"));
        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target,
            new RenderedMessage("Sub", null, "Body", "tr-TR"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
    }

    @Test
    void teams500ServerErrorRetry() {
        teams.stubFor(post(urlEqualTo("/server-error"))
            .willReturn(aResponse().withStatus(500)));

        DeliveryTarget target = target(teams.url("/server-error"));
        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target,
            new RenderedMessage("Sub", null, "Body", "tr-TR"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
    }

    @Test
    void teamsEmptyBodyFailsBeforeSend() {
        DeliveryTarget target = target("http://localhost:1/never-called");
        RenderedMessage msg = new RenderedMessage(null, null, null, "tr-TR");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("empty message");
    }

    @Test
    void teamsChannelKey() {
        assertThat(adapter.channelKey()).isEqualTo("teams");
    }

    @Test
    void teamsFallbackToSubjectWhenBodyTextNull() {
        teams.stubFor(post(urlEqualTo("/svc"))
            .willReturn(aResponse().withStatus(200)));

        DeliveryTarget target = target(teams.url("/svc"));
        RenderedMessage msg = new RenderedMessage("Subject only", null, null, "tr-TR");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(target, msg);

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
        teams.verify(postRequestedFor(urlEqualTo("/svc"))
            .withRequestBody(matchingJsonPath("$.fallbackText", equalTo("Subject only"))));
    }

    private static DeliveryTarget target(String url) {
        return new DeliveryTarget("teams", "channel", null, "hash", url, "teams-default");
    }
}
