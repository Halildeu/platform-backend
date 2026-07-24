package com.example.ethics.config;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fail-closed controls for the Etik Speak notification intent outbox.
 *
 * <p>The worker is disabled unless the TEST/production overlay explicitly
 * supplies a dedicated service identity and recipient. Credentials are never
 * included in {@link #toString()} because this class deliberately keeps the
 * default {@code Object} representation.
 */
@ConfigurationProperties(prefix = "ethics.notification-delivery")
public class NotificationDeliveryProperties {
    private boolean enabled;
    private Duration pollDelay = Duration.ofSeconds(5);
    private int batchSize = 25;
    private Duration leaseDuration = Duration.ofSeconds(60);
    private Duration retryBaseDelay = Duration.ofSeconds(10);
    private Duration retryMaxDelay = Duration.ofMinutes(15);
    private int maxAttempts = 8;
    private String owner = "ethics-notification-worker";
    private String orchestratorBaseUrl = "http://notification-orchestrator:8089";
    private String tokenUrl = "http://auth-service:8088/oauth2/token";
    private String clientId = "ethics-service";
    private String clientSecret = "";
    private String tokenAudience = "notification-orchestrator";
    private List<String> tokenPermissions = List.of("notify:intents:system");
    private String recipientSubscriberId = "";
    private String locale = "tr-TR";
    private String channel = "inapp";
    private Duration httpTimeout = Duration.ofSeconds(3);

    @PostConstruct
    public void validate() {
        if (pollDelay == null || pollDelay.isNegative() || pollDelay.isZero()) {
            throw new IllegalArgumentException(
                    "ethics.notification-delivery.poll-delay must be positive");
        }
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException(
                    "ethics.notification-delivery.batch-size must be 1..500");
        }
        if (leaseDuration == null || leaseDuration.compareTo(Duration.ofSeconds(5)) < 0) {
            throw new IllegalArgumentException(
                    "ethics.notification-delivery.lease-duration must be >= 5s");
        }
        if (retryBaseDelay == null || retryBaseDelay.isNegative() || retryBaseDelay.isZero()) {
            throw new IllegalArgumentException(
                    "ethics.notification-delivery.retry-base-delay must be positive");
        }
        if (retryMaxDelay == null || retryMaxDelay.compareTo(retryBaseDelay) < 0) {
            throw new IllegalArgumentException(
                    "ethics.notification-delivery.retry-max-delay must be >= retry-base-delay");
        }
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException(
                    "ethics.notification-delivery.max-attempts must be 1..100");
        }
        require(owner, "owner", 120);
        if (httpTimeout == null
                || httpTimeout.isNegative()
                || httpTimeout.isZero()
                || httpTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException(
                    "ethics.notification-delivery.http-timeout must be > 0 and <= 30s");
        }
        if (enabled) {
            require(orchestratorBaseUrl, "orchestrator-base-url", 500);
            require(tokenUrl, "token-url", 500);
            require(clientId, "client-id", 120);
            require(clientSecret, "client-secret", 1000);
            require(tokenAudience, "token-audience", 120);
            if (tokenPermissions == null
                    || tokenPermissions.isEmpty()
                    || tokenPermissions.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException(
                        "ethics.notification-delivery.token-permissions is required");
            }
            require(recipientSubscriberId, "recipient-subscriber-id", 128);
            require(locale, "locale", 16);
            require(channel, "channel", 32);
        }
    }

    private static void require(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    "ethics.notification-delivery." + name + " is required and <= "
                            + maxLength + " chars");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getPollDelay() { return pollDelay; }
    public void setPollDelay(Duration pollDelay) { this.pollDelay = pollDelay; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
    public Duration getRetryBaseDelay() { return retryBaseDelay; }
    public void setRetryBaseDelay(Duration retryBaseDelay) { this.retryBaseDelay = retryBaseDelay; }
    public Duration getRetryMaxDelay() { return retryMaxDelay; }
    public void setRetryMaxDelay(Duration retryMaxDelay) { this.retryMaxDelay = retryMaxDelay; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getOrchestratorBaseUrl() { return orchestratorBaseUrl; }
    public void setOrchestratorBaseUrl(String orchestratorBaseUrl) {
        this.orchestratorBaseUrl = orchestratorBaseUrl;
    }
    public String getTokenUrl() { return tokenUrl; }
    public void setTokenUrl(String tokenUrl) { this.tokenUrl = tokenUrl; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getTokenAudience() { return tokenAudience; }
    public void setTokenAudience(String tokenAudience) { this.tokenAudience = tokenAudience; }
    public List<String> getTokenPermissions() { return tokenPermissions; }
    public void setTokenPermissions(List<String> tokenPermissions) {
        this.tokenPermissions = tokenPermissions;
    }
    public String getRecipientSubscriberId() { return recipientSubscriberId; }
    public void setRecipientSubscriberId(String recipientSubscriberId) {
        this.recipientSubscriberId = recipientSubscriberId;
    }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public Duration getHttpTimeout() { return httpTimeout; }
    public void setHttpTimeout(Duration httpTimeout) { this.httpTimeout = httpTimeout; }
}
