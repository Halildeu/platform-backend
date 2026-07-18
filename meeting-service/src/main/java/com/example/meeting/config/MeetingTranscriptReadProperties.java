package com.example.meeting.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Independent activation and service-credential boundary for transcript readback. */
@Component
@ConfigurationProperties(prefix = "meeting.transcript-read")
public class MeetingTranscriptReadProperties {

    private boolean enabled;
    private String transcriptServiceBaseUrl = "http://transcript-service:8098";
    private String tokenUrl = "http://auth-service:8088/oauth2/token";
    private String clientId = "meeting-service";
    private String clientSecret = "";
    private int connectTimeoutMillis = 2_000;
    private int responseTimeoutMillis = 10_000;

    @PostConstruct
    void validate() {
        if (connectTimeoutMillis < 1 || responseTimeoutMillis < 1) {
            throw new IllegalStateException("meeting transcript read timeouts must be positive");
        }
        if (enabled && (isBlank(transcriptServiceBaseUrl) || isBlank(tokenUrl)
                || isBlank(clientId) || isBlank(clientSecret))) {
            throw new IllegalStateException(
                    "meeting transcript read service credentials are required when enabled");
        }
    }

    private static boolean isBlank(String value) { return value == null || value.isBlank(); }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { this.enabled = value; }
    public String getTranscriptServiceBaseUrl() { return transcriptServiceBaseUrl; }
    public void setTranscriptServiceBaseUrl(String value) { this.transcriptServiceBaseUrl = value; }
    public String getTokenUrl() { return tokenUrl; }
    public void setTokenUrl(String value) { this.tokenUrl = value; }
    public String getClientId() { return clientId; }
    public void setClientId(String value) { this.clientId = value; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String value) { this.clientSecret = value; }
    public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
    public void setConnectTimeoutMillis(int value) { this.connectTimeoutMillis = value; }
    public int getResponseTimeoutMillis() { return responseTimeoutMillis; }
    public void setResponseTimeoutMillis(int value) { this.responseTimeoutMillis = value; }
}
