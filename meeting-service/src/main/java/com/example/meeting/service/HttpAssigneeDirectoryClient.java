package com.example.meeting.service;

import com.example.meeting.config.MeetingAssigneeDirectoryProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Service-token client for user-service's internal subject resolution
 * ({@code GET /api/users/internal/{id}/impersonation-target}); mirrors
 * {@code HttpCanonicalTranscriptClient}'s 401-invalidate-retry shape.
 */
@Component
public class HttpAssigneeDirectoryClient implements AssigneeDirectoryClient {

    private final MeetingAssigneeDirectoryProperties properties;
    private final AssigneeDirectoryTokenProvider tokens;
    private final RestClient restClient;

    @Autowired
    public HttpAssigneeDirectoryClient(
            MeetingAssigneeDirectoryProperties properties,
            AssigneeDirectoryTokenProvider tokens,
            RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMillis());
        requestFactory.setReadTimeout(properties.getResponseTimeoutMillis());
        this.properties = properties;
        this.tokens = tokens;
        this.restClient = builder.clone().requestFactory(requestFactory).build();
    }

    HttpAssigneeDirectoryClient(
            MeetingAssigneeDirectoryProperties properties,
            AssigneeDirectoryTokenProvider tokens,
            RestClient restClient) {
        this.properties = properties;
        this.tokens = tokens;
        this.restClient = restClient;
    }

    @Override
    public Optional<String> resolveKcSubject(long userId) {
        if (!properties.isEnabled()) {
            throw new ResolutionUnavailableException("assignee directory disabled");
        }
        try {
            return call(userId);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                tokens.invalidate();
                try {
                    return call(userId);
                } catch (RestClientResponseException retry) {
                    return mapped(retry);
                } catch (RestClientException | IllegalStateException retry) {
                    throw new ResolutionUnavailableException(
                            "assignee directory unavailable after token refresh");
                }
            }
            return mapped(ex);
        } catch (RestClientException | IllegalStateException ex) {
            throw new ResolutionUnavailableException("assignee directory unavailable");
        }
    }

    private Optional<String> mapped(RestClientResponseException ex) {
        if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
            return Optional.empty();
        }
        throw new ResolutionUnavailableException(
                "assignee directory returned " + ex.getStatusCode().value());
    }

    private Optional<String> call(long userId) {
        Target target = restClient.get()
                .uri(properties.getUserServiceBaseUrl()
                        + "/api/users/internal/{userId}/impersonation-target", userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                .retrieve()
                .body(Target.class);
        if (target == null || !target.enabled()
                || target.kcSubject() == null || target.kcSubject().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(target.kcSubject());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Target(
            @JsonProperty("id") Long id,
            @JsonProperty("kcSubject") String kcSubject,
            @JsonProperty("enabled") boolean enabled) {
    }
}
