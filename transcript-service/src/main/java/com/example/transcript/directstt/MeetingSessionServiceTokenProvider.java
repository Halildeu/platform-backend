package com.example.transcript.directstt;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** Short-TTL client-credentials token cache. Secrets and tokens are never logged. */
@Component
class MeetingSessionServiceTokenProvider {

    private final DirectSttTranscriptResultConsumerProperties properties;
    private final RestClient restClient;
    private final Clock clock;
    private String cachedToken;
    private Instant expiresAt = Instant.EPOCH;

    @Autowired
    MeetingSessionServiceTokenProvider(
            DirectSttTranscriptResultConsumerProperties properties,
            RestClient.Builder builder) {
        this(properties, builder.clone()
                .requestFactory(HttpMeetingSessionResolver.requestFactory(properties))
                .build(), Clock.systemUTC());
    }

    MeetingSessionServiceTokenProvider(
            DirectSttTranscriptResultConsumerProperties properties,
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
        var cfg = properties.getMapping();
        if (!cfg.isEnabled() || isBlank(cfg.getClientId()) || isBlank(cfg.getClientSecret())) {
            throw new IllegalStateException("meeting session resolver credentials are unavailable");
        }
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("audience", "meeting-service");
        form.add("permissions", "meeting:session:resolve");
        TokenResponse response = restClient.post()
                .uri(cfg.getTokenUrl())
                .headers(headers -> headers.setBasicAuth(cfg.getClientId(), cfg.getClientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        if (response == null || isBlank(response.accessToken())) {
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn) {
    }
}
