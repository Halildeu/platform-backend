package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.dto.v1.admin.RolloutFailureEscalationResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Minimal GitHub REST issue publisher for #527 escalation projections. This is
 * intentionally narrow: POST one issue, bounded response read, redirects off,
 * short timeouts, and redacted failure messages.
 */
final class GitHubRolloutFailureIssuePublisher implements RolloutFailureIssuePublisher {

    private final RolloutFailureGithubEscalationProperties properties;
    private final HttpClient http;
    private final ObjectMapper objectMapper = new ObjectMapper();

    GitHubRolloutFailureIssuePublisher(RolloutFailureGithubEscalationProperties properties,
                                       HttpClient http) {
        this.properties = properties;
        this.http = http;
    }

    @Override
    public PublishedIssue createIssue(RolloutFailureEscalationResponse issue) {
        String body = writeBody(issue);
        HttpRequest request = HttpRequest.newBuilder(issueUri())
                .timeout(properties.readTimeout())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json")
                .header("User-Agent", properties.userAgent())
                .header("Authorization", "Bearer " + properties.token())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        final HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception ex) {
            throw new GitHubIssuePublishException("GitHub issue create transport error: "
                    + ex.getClass().getSimpleName());
        }
        if (response.statusCode() != 201) {
            closeQuietly(response.body());
            throw new GitHubIssuePublishException("GitHub issue create failed: HTTP " + response.statusCode());
        }
        JsonNode root = readJson(response);
        String htmlUrl = root.path("html_url").asText(null);
        long number = root.path("number").asLong(0);
        if (htmlUrl == null || htmlUrl.isBlank() || number <= 0) {
            throw new GitHubIssuePublishException("GitHub issue create response missing html_url/number");
        }
        return new PublishedIssue(htmlUrl, number);
    }

    private String writeBody(RolloutFailureEscalationResponse issue) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "title", issue.issueTitle(),
                    "body", issue.issueBody(),
                    "labels", issue.labels()));
        } catch (Exception ex) {
            throw new GitHubIssuePublishException("GitHub issue request serialization failed: "
                    + ex.getClass().getSimpleName());
        }
    }

    private JsonNode readJson(HttpResponse<InputStream> response) {
        int cap = properties.maxResponseBytes();
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes(cap + 1);
            if (bytes.length > cap) {
                throw new GitHubIssuePublishException("GitHub issue create response exceeds "
                        + cap + " bytes (capped)");
            }
            return objectMapper.readTree(bytes);
        } catch (GitHubIssuePublishException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new GitHubIssuePublishException("GitHub issue create response parse error: "
                    + ex.getClass().getSimpleName());
        }
    }

    private URI issueUri() {
        return URI.create(properties.apiBaseUrl() + "/repos/"
                + properties.owner() + "/" + properties.repo() + "/issues");
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (Exception ignored) {
            // no-op
        }
    }

    static final class GitHubIssuePublishException extends RuntimeException {
        GitHubIssuePublishException(String message) {
            super(message);
        }
    }
}
