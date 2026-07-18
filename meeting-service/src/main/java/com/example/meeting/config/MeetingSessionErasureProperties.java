package com.example.meeting.config;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "meeting.session-erasure")
public class MeetingSessionErasureProperties {

    private boolean enabled;
    private boolean schedulingEnabled = true;
    private String transcriptServiceBaseUrl = "http://transcript-service:8098";
    private String tokenUrl = "http://auth-service:8088/oauth2/token";
    private String clientId = "meeting-service";
    private String clientSecret = "";
    private int connectTimeoutMillis = 2_000;
    private int responseTimeoutMillis = 10_000;
    private int batchSize = 25;
    private Duration leaseDuration = Duration.ofMinutes(1);
    private Duration retryDelay = Duration.ofSeconds(30);
    private Duration heldRetryDelay = Duration.ofMinutes(5);
    private String owner = "";

    @PostConstruct
    void validate() {
        if (batchSize < 1 || connectTimeoutMillis < 1 || responseTimeoutMillis < 1
                || leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()
                || retryDelay == null || retryDelay.isNegative() || retryDelay.isZero()
                || heldRetryDelay == null || heldRetryDelay.isNegative() || heldRetryDelay.isZero()) {
            throw new IllegalStateException("meeting session erasure timing and batch settings must be positive");
        }
        if (enabled && (isBlank(transcriptServiceBaseUrl) || isBlank(tokenUrl)
                || isBlank(clientId) || isBlank(clientSecret))) {
            throw new IllegalStateException("meeting session erasure service credentials are required when enabled");
        }
    }

    private static boolean isBlank(String value) { return value == null || value.isBlank(); }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isSchedulingEnabled() { return schedulingEnabled; }
    public void setSchedulingEnabled(boolean schedulingEnabled) { this.schedulingEnabled = schedulingEnabled; }
    public String getTranscriptServiceBaseUrl() { return transcriptServiceBaseUrl; }
    public void setTranscriptServiceBaseUrl(String value) { this.transcriptServiceBaseUrl = value; }
    public String getTokenUrl() { return tokenUrl; }
    public void setTokenUrl(String tokenUrl) { this.tokenUrl = tokenUrl; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
    public void setConnectTimeoutMillis(int value) { this.connectTimeoutMillis = value; }
    public int getResponseTimeoutMillis() { return responseTimeoutMillis; }
    public void setResponseTimeoutMillis(int value) { this.responseTimeoutMillis = value; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
    public Duration getRetryDelay() { return retryDelay; }
    public void setRetryDelay(Duration retryDelay) { this.retryDelay = retryDelay; }
    public Duration getHeldRetryDelay() { return heldRetryDelay; }
    public void setHeldRetryDelay(Duration value) { this.heldRetryDelay = value; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
}
