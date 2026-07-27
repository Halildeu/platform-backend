package com.example.ethics.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ES-203/C — the user-directory boundary for display names.
 *
 * <p>Names are pass-through only: they cross this service on the way to an
 * authorized staff response and are never written to the database, the audit
 * ledger, a log line, a cache or a backup. The properties therefore carry no
 * storage knobs at all — there is nothing to store.
 *
 * <p>The client identity is the same {@code ethics-service} registration the
 * notification path uses (one credential in Vault, no rotation drift); what
 * differs is the audience and the permission, which are deliberately narrow:
 * {@code users:display-names:read} can resolve a name and nothing else.
 */
@ConfigurationProperties(prefix = "ethics.user-directory")
public record UserDirectoryProperties(
        String baseUrl,
        String tokenUrl,
        String clientId,
        String clientSecret,
        String tokenAudience,
        String tokenPermission,
        Duration httpTimeout,
        Integer batchLimit) {
    public UserDirectoryProperties {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://user-service:8089";
        if (tokenUrl == null || tokenUrl.isBlank()) tokenUrl = "http://auth-service:8088/oauth2/token";
        if (clientId == null || clientId.isBlank()) clientId = "ethics-service";
        if (tokenAudience == null || tokenAudience.isBlank()) tokenAudience = "user-service";
        if (tokenPermission == null || tokenPermission.isBlank()) tokenPermission = "users:display-names:read";
        if (httpTimeout == null || httpTimeout.isNegative() || httpTimeout.isZero()) httpTimeout = Duration.ofSeconds(3);
        if (batchLimit == null || batchLimit < 1 || batchLimit > 200) batchLimit = 200;
        // clientSecret may be blank: the directory is then unavailable and both
        // consumers take their declared failure path (503 on the decision
        // surface, null names on the display surface). Boot does not fail —
        // name resolution is a dependency of the picker, not of the service.
    }
}
