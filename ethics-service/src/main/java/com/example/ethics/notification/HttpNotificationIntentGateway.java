package com.example.ethics.notification;

import com.example.ethics.config.NotificationDeliveryProperties;
import com.example.ethics.model.NotificationOutbox;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Dedicated service-identity client for notification-orchestrator.
 *
 * <p>Tokens and client credentials are held only in memory and never logged.
 * A provider/API failure is propagated to the durable outbox worker, which
 * applies bounded retry and DLQ state without touching the business commit.
 */
@Component
public class HttpNotificationIntentGateway implements NotificationIntentGateway {
    private final NotificationDeliveryProperties properties;
    private final NotificationIntentPayloadFactory payloads;
    private final RestClient http;
    private volatile CachedToken cachedToken;

    @Autowired
    public HttpNotificationIntentGateway(
            NotificationDeliveryProperties properties,
            NotificationIntentPayloadFactory payloads,
            RestClient.Builder builder) {
        this(properties, payloads, buildHttp(properties, builder));
    }

    HttpNotificationIntentGateway(
            NotificationDeliveryProperties properties,
            NotificationIntentPayloadFactory payloads,
            RestClient http) {
        this.properties = properties;
        this.payloads = payloads;
        this.http = http;
    }

    private static RestClient buildHttp(
            NotificationDeliveryProperties properties,
            RestClient.Builder builder) {
        var requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(properties.getHttpTimeout());
        return builder.requestFactory(requestFactory).build();
    }

    @Override
    public void submit(NotificationOutbox row) {
        http.post()
                .uri(properties.getOrchestratorBaseUrl()
                        + "/api/v1/internal/notify/intents")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .body(payloads.build(row))
                .retrieve()
                .toBodilessEntity();
    }

    private String token() {
        Instant now = Instant.now();
        CachedToken local = cachedToken;
        if (local == null || !now.isBefore(local.refreshAfter())) {
            synchronized (this) {
                local = cachedToken;
                if (local == null || !now.isBefore(local.refreshAfter())) {
                    local = mintToken(now);
                    cachedToken = local;
                }
            }
        }
        return local.value();
    }

    private CachedToken mintToken(Instant now) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("audience", properties.getTokenAudience());
        for (String permission : properties.getTokenPermissions()) {
            form.add("permissions", permission);
        }

        String basic = Base64.getEncoder().encodeToString(
                (properties.getClientId() + ":" + properties.getClientSecret())
                        .getBytes(StandardCharsets.UTF_8));
        Map<?, ?> response = http.post()
                .uri(properties.getTokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (response == null
                || !(response.get("access_token") instanceof String accessToken)
                || accessToken.isBlank()) {
            throw new IllegalStateException("Ethics notification service token response was invalid");
        }
        long expiresIn = response.get("expires_in") instanceof Number number
                ? number.longValue()
                : 60L;
        long refreshSeconds = Math.max(5L, expiresIn - Math.min(10L, expiresIn / 2L));
        return new CachedToken(accessToken, now.plusSeconds(refreshSeconds));
    }

    private record CachedToken(String value, Instant refreshAfter) {
    }
}
