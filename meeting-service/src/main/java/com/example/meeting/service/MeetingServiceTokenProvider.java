package com.example.meeting.service;

import com.example.meeting.config.MeetingSessionErasureProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** Short-lived service credential cache. Secrets and bearer tokens are never logged. */
@Component
class MeetingServiceTokenProvider {

    private final MeetingSessionErasureProperties properties;
    private final RestClient restClient;
    private final Clock clock;
    private String cachedToken;
    private Instant expiresAt = Instant.EPOCH;

    @Autowired
    MeetingServiceTokenProvider(
            MeetingSessionErasureProperties properties,
            RestClient.Builder builder) {
        this(properties, builder.clone().build(), Clock.systemUTC());
    }

    MeetingServiceTokenProvider(
            MeetingSessionErasureProperties properties,
            RestClient restClient,
            Clock clock) {
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
            throw new IllegalStateException("session erasure credentials are unavailable");
        }
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("audience", "transcript-service");
        form.add("permissions", "transcript:session:erase");
        TokenResponse response = restClient.post()
                .uri(properties.getTokenUrl())
                .headers(headers -> headers.setBasicAuth(
                        properties.getClientId(), properties.getClientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new IllegalStateException("service token endpoint returned no token");
        }
        cachedToken = response.accessToken();
        expiresAt = now.plusSeconds(Math.max(10L, response.expiresIn()));
        return cachedToken;
    }

    synchronized void invalidate() {
        cachedToken = null;
        expiresAt = Instant.EPOCH;
    }

    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn) {
    }
}
