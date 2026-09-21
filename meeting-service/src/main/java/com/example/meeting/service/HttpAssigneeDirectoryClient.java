package com.example.meeting.service;

import com.example.meeting.config.MeetingAssigneeDirectoryProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

    @Override
    public Optional<Long> resolveUserId(String issuer, String kcSubject) {
        if (!properties.isEnabled()) {
            throw new ResolutionUnavailableException("assignee directory disabled");
        }
        if (kcSubject == null || kcSubject.isBlank()) {
            return Optional.empty();
        }
        try {
            return resolve(issuer, kcSubject);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                tokens.invalidate();
                try {
                    return resolve(issuer, kcSubject);
                } catch (RestClientResponseException retry) {
                    return mappedUser(retry);
                } catch (RestClientException | IllegalStateException retry) {
                    throw new ResolutionUnavailableException(
                            "assignee directory unavailable after token refresh");
                }
            }
            return mappedUser(ex);
        } catch (RestClientException | IllegalStateException ex) {
            throw new ResolutionUnavailableException("assignee directory unavailable");
        }
    }

    private Optional<Long> mappedUser(RestClientResponseException ex) {
        if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
            return Optional.empty();
        }
        throw new ResolutionUnavailableException(
                "assignee directory returned " + ex.getStatusCode().value());
    }

    @Override
    public Optional<NotificationIdentity> resolveNotificationIdentity(String issuer, String principal) {
        if (!properties.isEnabled()) throw new ResolutionUnavailableException("assignee directory disabled");
        Long numeric = principal.matches("[1-9][0-9]*") ? Long.valueOf(principal) : null;
        String subject = numeric == null ? principal : resolveKcSubject(numeric).orElse(null);
        if (subject == null) return Optional.empty();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Resolved r = restClient.post()
                        .uri(properties.getUserServiceBaseUrl() + "/api/users/internal/authenticated-principal/resolve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                        .contentType(MediaType.APPLICATION_JSON).body(new ResolveRequest(issuer, subject, null))
                        .retrieve().body(Resolved.class);
                if (r == null || r.userId() == null || r.userId() <= 0 || !r.subjectMatched()
                        || (numeric != null && !numeric.equals(r.userId()))) return Optional.empty();
                return Optional.of(new NotificationIdentity(r.userId(), r.enabled(), r.deleted(), r.companyId()));
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode().value() == 404) return Optional.empty();
                if (ex.getStatusCode().value() == 401 && attempt == 0) { tokens.invalidate(); continue; }
                throw new ResolutionUnavailableException("notification directory unavailable");
            } catch (RestClientException ex) {
                throw new ResolutionUnavailableException("notification directory unavailable");
            }
        }
        throw new ResolutionUnavailableException("notification directory unavailable");
    }

    private Optional<Long> resolve(String issuer, String kcSubject) {
        Resolved resolved = restClient.post()
                .uri(properties.getUserServiceBaseUrl()
                        + "/api/users/internal/authenticated-principal/resolve")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ResolveRequest(issuer, kcSubject, null))
                .retrieve()
                .body(Resolved.class);
        if (resolved == null || resolved.userId() == null || resolved.userId() <= 0) {
            return Optional.empty();
        }
        return Optional.of(resolved.userId());
    }

    record ResolveRequest(
            @JsonProperty("issuer") String issuer,
            @JsonProperty("subject") String subject,
            @JsonProperty("email") String email) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Resolved(
            @JsonProperty("userId") Long userId,
            @JsonProperty("subjectMatched") boolean subjectMatched,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("deleted") boolean deleted,
            @JsonProperty("companyId") Long companyId) {
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
