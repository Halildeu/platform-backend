package com.example.meeting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Faz 24 Görevler dilim-4b (gitops#3486 / #3537): delivery of
 * {@code meeting.action.assigned} / {@code meeting.action.reassigned} outbox
 * events to notification-orchestrator as system intents, so the assignee sees
 * the assignment in their inbox.
 *
 * <p>Default-off: the outbox poller keeps publishing to Redis exactly as before
 * unless {@code enabled} is true AND a client secret is present.
 */
@Configuration
@ConfigurationProperties(prefix = "meeting.notify")
public class MeetingNotifyProperties {

    private boolean enabled;
    private String orchestratorBaseUrl = "http://notification-orchestrator:8089";
    private String tokenUrl = "http://auth-service:8088/oauth2/token";
    private String clientId = "meeting-service";
    private String clientSecret = "";
    /** Issuer sent to user-service when resolving the assignee subject to a platform user id. */
    private String subjectIssuer = "";
    private String locale = "tr-TR";
    private String channel = "in-app";
    private int connectTimeoutMillis = 2_000;
    private int responseTimeoutMillis = 10_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getOrchestratorBaseUrl() { return orchestratorBaseUrl; }
    public void setOrchestratorBaseUrl(String orchestratorBaseUrl) { this.orchestratorBaseUrl = orchestratorBaseUrl; }
    public String getTokenUrl() { return tokenUrl; }
    public void setTokenUrl(String tokenUrl) { this.tokenUrl = tokenUrl; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getSubjectIssuer() { return subjectIssuer; }
    public void setSubjectIssuer(String subjectIssuer) { this.subjectIssuer = subjectIssuer; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
    public void setConnectTimeoutMillis(int connectTimeoutMillis) { this.connectTimeoutMillis = connectTimeoutMillis; }
    public int getResponseTimeoutMillis() { return responseTimeoutMillis; }
    public void setResponseTimeoutMillis(int responseTimeoutMillis) { this.responseTimeoutMillis = responseTimeoutMillis; }
}
