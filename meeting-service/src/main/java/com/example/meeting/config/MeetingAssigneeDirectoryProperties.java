package com.example.meeting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Faz 24 Görevler (gitops#3507) — server-side assignee resolution.
 *
 * <p>The public user directory deliberately does NOT expose {@code kc_subject}
 * to browsers (user-service, Codex 019e1bed), and durable action ownership
 * must not use mutable numeric userId claims ({@link
 * com.example.meeting.security.AdminTenantContext}). UIs therefore send the
 * directory's numeric {@code assigneeUserId}; meeting-service resolves it to
 * the stable KC subject through user-service's service-token protected
 * internal endpoint before persisting.
 */
@Component
@ConfigurationProperties(prefix = "meeting.assignee-directory")
public class MeetingAssigneeDirectoryProperties {

    private boolean enabled;
    private String userServiceBaseUrl = "http://user-service:8089";
    private String tokenUrl = "http://auth-service:8088/oauth2/token";
    private String clientId = "meeting-service";
    private String clientSecret = "";
    private int connectTimeoutMillis = 2_000;
    private int responseTimeoutMillis = 10_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUserServiceBaseUrl() {
        return userServiceBaseUrl;
    }

    public void setUserServiceBaseUrl(String userServiceBaseUrl) {
        this.userServiceBaseUrl = userServiceBaseUrl;
    }

    public String getTokenUrl() {
        return tokenUrl;
    }

    public void setTokenUrl(String tokenUrl) {
        this.tokenUrl = tokenUrl;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public int getResponseTimeoutMillis() {
        return responseTimeoutMillis;
    }

    public void setResponseTimeoutMillis(int responseTimeoutMillis) {
        this.responseTimeoutMillis = responseTimeoutMillis;
    }
}
