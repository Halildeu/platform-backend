package com.example.meeting.service;

import com.example.meeting.config.MeetingTranscriptReadProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** Exact-permission token cache; secrets and bearer values never enter logs. */
@Component
class MeetingTranscriptReadTokenProvider {

    private final MeetingTranscriptReadProperties properties;
    private final RestClient restClient;
    private final Clock clock;
    private final java.util.Map<String, CachedToken> cache = new java.util.HashMap<>();
    private record CachedToken(String value, Instant expiresAt) { }

    @Autowired
    MeetingTranscriptReadTokenProvider(
            MeetingTranscriptReadProperties properties,
            RestClient.Builder builder) {
        this(properties, boundedClient(properties, builder), Clock.systemUTC());
    }

    MeetingTranscriptReadTokenProvider(
            MeetingTranscriptReadProperties properties,
            RestClient restClient,
            Clock clock) {
        this.properties = properties;
        this.restClient = restClient;
        this.clock = clock;
    }

    synchronized String token() {
        return tokenFor("transcript:canonical:read");
    }

    private static RestClient boundedClient(MeetingTranscriptReadProperties properties, RestClient.Builder builder) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMillis());
        factory.setReadTimeout(properties.getResponseTimeoutMillis());
        return builder.clone().requestFactory(factory).build();
    }

    synchronized String speakerLabelToken(boolean write) {
        if (!properties.isSpeakerLabelsEnabled())
            throw new IllegalStateException("SPEAKER_LABELS_DISABLED");
        return tokenFor(write ? "transcript:speaker-label:write" : "transcript:speaker-label:read");
    }

    private String tokenFor(String permission) {
        Instant now = clock.instant();
        CachedToken cached = cache.get(permission);
        if (cached != null && cached.expiresAt().isAfter(now.plusSeconds(5))) {
            return cached.value();
        }
        if (!properties.isEnabled() || properties.getClientSecret() == null
                || properties.getClientSecret().isBlank()) {
            throw new CanonicalTranscriptClient.ReadFailure(
                    CanonicalTranscriptClient.Failure.UNAVAILABLE);
        }
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("audience", "transcript-service");
        form.add("permissions", permission);
        TokenResponse response = restClient.post()
                .uri(properties.getTokenUrl())
                .headers(headers -> headers.setBasicAuth(
                        properties.getClientId(), properties.getClientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new CanonicalTranscriptClient.ReadFailure(
                    CanonicalTranscriptClient.Failure.UNAVAILABLE);
        }
        cache.put(permission, new CachedToken(response.accessToken(),
                now.plusSeconds(Math.max(10L, response.expiresIn()))));
        return response.accessToken();
    }

    synchronized void invalidate() {
        cache.clear();
    }

    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn) { }
}
