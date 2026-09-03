package com.example.meeting.notify;

import com.example.meeting.config.MeetingNotifyProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Exact-permission token cache for the notification-orchestrator system-intent
 * call (audience {@code notification-orchestrator}, permission
 * {@code notify:intents:system}); mirrors {@code AssigneeDirectoryTokenProvider}.
 * Secrets and bearer values never enter logs.
 */
@Component
class NotifyIntentTokenProvider {

    private final MeetingNotifyProperties properties;
    private final RestClient restClient;
    private final Clock clock;
    private String cachedToken;
    private Instant expiresAt = Instant.EPOCH;

    @Autowired
    NotifyIntentTokenProvider(MeetingNotifyProperties properties, RestClient.Builder builder) {
        this(properties, builder.clone().build(), Clock.systemUTC());
    }

    NotifyIntentTokenProvider(MeetingNotifyProperties properties, RestClient restClient, Clock clock) {
        this.properties = properties;
        this.restClient = restClient;
        this.clock = clock;
    }

    synchronized String token() {
        Instant now = clock.instant();
        if (cachedToken != null && expiresAt.isAfter(now.plusSeconds(5))) {
            return cachedToken;
        }
        if (!properties.isEnabled() || properties.getClientSecret() == null
                || properties.getClientSecret().isBlank()) {
            throw new IllegalStateException("notify intent credentials are unavailable");
        }
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("audience", "notification-orchestrator");
        form.add("permissions", "notify:intents:system");
        TokenResponse response = restClient.post()
                .uri(properties.getTokenUrl())
                .headers(headers -> headers.setBasicAuth(
                        properties.getClientId(), properties.getClientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new IllegalStateException("notify intent token mint returned no token");
        }
        cachedToken = response.accessToken();
        expiresAt = now.plusSeconds(Math.max(0, response.expiresIn()));
        return cachedToken;
    }

    synchronized void invalidate() {
        cachedToken = null;
        expiresAt = Instant.EPOCH;
    }

    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn) {
    }
}
