package com.serban.notify.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.template.RenderedMessage;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Microsoft Teams Power Automate flow webhook adapter (Faz 23.6 M7
 * T4.1.2 — Codex {@code 019e496d} AGREE PARTIAL iter-1).
 *
 * <p>Office 365 Connectors deprecated (2024). Power Automate flow
 * webhook URL'leri kullanılır:
 * <ol>
 *   <li>Müşteri/admin Power Automate'te yeni bir flow oluşturur</li>
 *   <li>Flow trigger: "When a HTTP request is received"</li>
 *   <li>Flow action: "Post adaptive card in a chat or channel"</li>
 *   <li>Generated webhook URL → Vault'a seedlenir →
 *       {@code notify.adapters.teams.default-flow-url}</li>
 *   <li>Adapter HTTP POST + Adaptive Card payload → flow execute eder</li>
 * </ol>
 *
 * <p>Codex 019e496d Q3 absorb: <b>payload mode flag YOK</b>. Teams
 * yeni adapter; backward-compat yükü yok. Tek davranış: Adaptive Card.
 * (İleride mode flag gerekirse {@code notify.adapters.teams.payload-mode}
 * eklenebilir.)
 *
 * <p>Codex 019e496d Q7 absorb (retry sınıflandırması REVISE):
 * <ul>
 *   <li>HTTP 2xx → DELIVERED</li>
 *   <li>HTTP 400, 401, 403, 404, 410 → FAILED (permanent: invalid URL,
 *       expired flow, malformed payload, insufficient permission)</li>
 *   <li>HTTP 408, 429 → RETRY (timeout, throttling — Power Automate
 *       quota'sı yoğun saatlerde 429 üretebilir)</li>
 *   <li>HTTP 3xx, 5xx, timeout, IOException → RETRY (transient)</li>
 * </ul>
 *
 * <p>Threading + Graph API path deferred (Codex iter-1 split):
 * Power Automate connector path threading desteklemiyor; aynı
 * correlation_id mesajları aynı channel'a düz akış. Native Teams
 * threading için Graph API + Azure App Registration + admin consent
 * gerekir — ayrı sprint (separate PR).
 */
@Component
public class TeamsPowerAutomateAdapter implements ChannelAdapter {

    private static final Logger log =
        LoggerFactory.getLogger(TeamsPowerAutomateAdapter.class);
    private static final int CONNECT_TIMEOUT_SEC = 5;
    private static final int RESPONSE_TIMEOUT_SEC = 10;

    private final ObjectMapper objectMapper;

    public TeamsPowerAutomateAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        log.info("TeamsPowerAutomateAdapter activated (Adaptive Card v1.4 payload)");
    }

    @Override
    public String channelKey() {
        return "teams";
    }

    @Override
    public DeliveryAttemptResult send(DeliveryTarget target, RenderedMessage message) {
        String text = (message.bodyText() != null && !message.bodyText().isBlank())
            ? message.bodyText()
            : message.subject();
        if (text == null || text.isBlank()) {
            return DeliveryAttemptResult.failed("empty message (no body_text or subject)", null);
        }

        Map<String, Object> payloadMap =
            TeamsAdaptiveCardPayloadBuilder.build(target, message, text);

        try (CloseableHttpClient client = newClient()) {
            HttpPost post = new HttpPost(target.targetRef());
            post.setHeader("Content-Type", "application/json; charset=utf-8");
            String payload = objectMapper.writeValueAsString(payloadMap);
            post.setEntity(new StringEntity(payload, StandardCharsets.UTF_8));

            String providerMsgId = "teams-" + UUID.randomUUID();
            return client.execute(post, response -> {
                int code = response.getCode();
                if (code >= 200 && code < 300) {
                    log.info("teams delivered: target=<redacted> code={} msg_id={}",
                        code, providerMsgId);
                    return DeliveryAttemptResult.delivered(providerMsgId);
                }
                if (code == 408 || code == 429) {
                    // Throttling + request timeout — transient.
                    log.warn("teams transient RETRY (throttle/timeout): code={}", code);
                    return DeliveryAttemptResult.retry("HTTP " + code, code);
                }
                if (code >= 400 && code < 500) {
                    // 400/401/403/404/410 — permanent (invalid URL/payload/permission).
                    log.warn("teams permanent FAIL: code={}", code);
                    return DeliveryAttemptResult.failed("HTTP " + code, code);
                }
                // 3xx (redirect; Power Automate doesn't normally use redirects;
                // treat as transient defensive), 5xx — RETRY.
                log.warn("teams transient RETRY: code={}", code);
                return DeliveryAttemptResult.retry("HTTP " + code, code);
            });
        } catch (IOException e) {
            log.warn("teams IOException (treating as RETRY): {}", e.getMessage());
            return DeliveryAttemptResult.retry("io: " + e.getClass().getSimpleName(), null);
        }
    }

    private static CloseableHttpClient newClient() {
        RequestConfig config = RequestConfig.custom()
            .setConnectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .setResponseTimeout(RESPONSE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build();
        return HttpClients.custom().setDefaultRequestConfig(config).build();
    }
}
