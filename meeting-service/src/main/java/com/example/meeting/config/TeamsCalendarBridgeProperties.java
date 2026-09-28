package com.example.meeting.config;

import java.net.URI;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "meeting.teams-calendar")
public class TeamsCalendarBridgeProperties {
    private boolean enabled;
    private String workerBaseUrl = "http://teams-capture-worker:8080";
    private String controlKey = "";
    private String microsoftTenantId = "";
    private String impersonationClientId = "impersonation-broker";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getWorkerBaseUrl() { return workerBaseUrl; }
    public void setWorkerBaseUrl(String value) { workerBaseUrl = value; }
    public String getControlKey() { return controlKey; }
    public void setControlKey(String value) { controlKey = value; }
    public String getMicrosoftTenantId() { return microsoftTenantId; }
    public void setMicrosoftTenantId(String value) { microsoftTenantId = value; }
    public String getImpersonationClientId() { return impersonationClientId; }
    public void setImpersonationClientId(String value) { impersonationClientId = value; }

    public boolean isConfigured() {
        try {
            URI uri = URI.create(workerBaseUrl);
            UUID tenant = UUID.fromString(microsoftTenantId);
            return enabled && controlKey != null && controlKey.length() >= 32 && controlKey.length() <= 1024
                    && controlKey.chars().noneMatch(Character::isISOControl)
                    && !tenant.equals(new UUID(0, 0)) && tenant.toString().equals(microsoftTenantId)
                    && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null && uri.getPath().isEmpty();
        } catch (RuntimeException invalid) { return false; }
    }
}
