package com.example.endpointadmin.service.rolloutfailure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Disabled-by-default GitHub issue creation configuration for #527 escalation
 * projections. The token must come from the environment or a mounted secret; it
 * is never hardcoded and {@link #toString()} redacts it.
 */
@ConfigurationProperties(prefix = "endpoint-admin.rollout-failure.github-escalation")
public record RolloutFailureGithubEscalationProperties(
        boolean enabled,
        String apiBaseUrl,
        String owner,
        String repo,
        String token,
        String userAgent,
        Duration connectTimeout,
        Duration readTimeout,
        int maxResponseBytes) {

    private static final String GITHUB_NAME = "^[A-Za-z0-9_.-]{1,100}$";

    public RolloutFailureGithubEscalationProperties {
        apiBaseUrl = blank(apiBaseUrl) ? "https://api.github.com" : stripTrailingSlash(apiBaseUrl.trim());
        owner = owner == null ? "" : owner.trim();
        repo = repo == null ? "" : repo.trim();
        token = token == null ? "" : token.trim();
        userAgent = blank(userAgent) ? "platform-endpoint-admin-fdq/1.0" : userAgent.trim();
        connectTimeout = positiveOr(connectTimeout, Duration.ofSeconds(5));
        readTimeout = positiveOr(readTimeout, Duration.ofSeconds(10));
        maxResponseBytes = maxResponseBytes <= 0 ? 64 * 1024 : maxResponseBytes;

        if (enabled) {
            if (!apiBaseUrl.toLowerCase().startsWith("https://")) {
                throw new IllegalStateException("github-escalation.apiBaseUrl must be https when enabled");
            }
            if (!owner.matches(GITHUB_NAME) || !repo.matches(GITHUB_NAME)) {
                throw new IllegalStateException("github-escalation owner/repo must be bounded GitHub names");
            }
            if (blank(token)) {
                throw new IllegalStateException("github-escalation.token is required when enabled");
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return (value == null || value.isZero() || value.isNegative()) ? fallback : value;
    }

    private static String stripTrailingSlash(String value) {
        String out = value;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    @Override
    public String toString() {
        return "RolloutFailureGithubEscalationProperties{enabled=" + enabled
                + ", apiBaseUrl=" + apiBaseUrl
                + ", owner=" + (blank(owner) ? "<unset>" : owner)
                + ", repo=" + (blank(repo) ? "<unset>" : repo)
                + ", token=" + (blank(token) ? "<unset>" : "<redacted>")
                + ", userAgent=" + userAgent
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout
                + ", maxResponseBytes=" + maxResponseBytes + "}";
    }
}
