package com.example.transcript.notify;

import com.example.transcript.events.TranscriptMeetingEventMessage;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Uses the meeting authority for recipients; own committed outbox retains retry ownership. */
@Component
@ConditionalOnProperty(prefix="transcript.notify", name="enabled", havingValue="true")
public class TranscriptReadyNotificationSink {
    private final TranscriptNotifyProperties properties;
    private final RestClient http;
    private final Map<String, CachedToken> tokens = new HashMap<>();
    @Autowired
    public TranscriptReadyNotificationSink(TranscriptNotifyProperties properties, RestClient.Builder builder) {
        this(properties, client(builder));
    }
    TranscriptReadyNotificationSink(TranscriptNotifyProperties properties, RestClient http) {
        this.properties = properties; this.http = http;
    }
    private static RestClient client(RestClient.Builder builder) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000); factory.setReadTimeout(10000);
        return builder.clone().requestFactory(factory).build();
    }
    public void deliver(TranscriptMeetingEventMessage event) {
        if (!"meeting.transcript.ready".equals(event.eventType())) return;
        UUID org = event.orgId() == null ? event.tenantId() : event.orgId();
        if (event.tenantId() == null || !event.tenantId().equals(org)) throw new IllegalArgumentException("Invalid notification scope");
        List<Long> recipients = authorized("meeting-service", () -> http.post()
                .uri(properties.getMeetingBaseUrl() + "/api/v1/internal/meetings/{id}/notification-recipients", event.meetingId())
                .headers(h -> h.setBearerAuth(token("meeting-service")))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("tenantId", event.tenantId(), "orgId", org))
                .retrieve().body(new ParameterizedTypeReference<List<Long>>() {}));
        if (recipients == null || recipients.size() > 1000 || recipients.stream().anyMatch(id -> id == null || id <= 0))
            throw new IllegalStateException("Invalid notification recipients");
        for (long user : new TreeSet<>(recipients)) {
            String key = event.eventKey() + "|native-ready|" + user;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("intentId", intentId(key)); body.put("idempotencyKey", key);
            body.put("correlationId", event.meetingId().toString()); body.put("orgId", org.toString());
            body.put("topicKey", event.eventType()); body.put("severity", "info"); body.put("dataClassification", "transactional");
            body.put("recipients", List.of(Map.of("type", "subscriber", "subscriberId", Long.toString(user), "locale", "tr-TR")));
            body.put("template", Map.of("templateId", event.eventType(), "version", 1, "locale", "tr-TR"));
            body.put("channels", List.of("push"));
            body.put("payload", Map.of("meetingId", event.meetingId().toString(), "pushAudience", "native"));
            authorized("notification-orchestrator", () -> http.post()
                    .uri(properties.getOrchestratorBaseUrl() + "/api/v1/internal/notify/intents")
                    .headers(h -> h.setBearerAuth(token("notification-orchestrator")))
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity());
        }
    }
    private <T> T authorized(String audience, Supplier<T> call) {
        try { return call.get(); }
        catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() != 401) throw ex;
            synchronized (this) { tokens.remove(audience); }
            return call.get();
        }
    }
    private synchronized String token(String audience) {
        var cached = tokens.get(audience);
        if (cached != null && cached.expiry().isAfter(Instant.now().plusSeconds(5))) return cached.value();
        if (!properties.isEnabled() || properties.getClientSecret().isBlank()) throw new IllegalStateException("notification credentials unavailable");
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials"); form.add("audience", audience);
        form.add("permissions", audience.equals("meeting-service") ? "meeting:notification:read" : "notify:intents:system");
        var response = http.post().uri(properties.getTokenUrl())
                .headers(h -> h.setBasicAuth(properties.getClientId(), properties.getClientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Token.class);
        if (response == null || response.value() == null || response.value().isBlank()) throw new IllegalStateException("notification token unavailable");
        tokens.put(audience, new CachedToken(response.value(), Instant.now().plusSeconds(Math.max(0, response.seconds()))));
        return response.value();
    }
    static String intentId(String key) {
        try { return "mtg-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)), 0, 24); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    record Token(@JsonProperty("access_token") String value, @JsonProperty("expires_in") long seconds) {}
    record CachedToken(String value, Instant expiry) {}
}
