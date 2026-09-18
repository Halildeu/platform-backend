package com.example.transcript.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix="transcript.notify")
public class TranscriptNotifyProperties {
    private boolean enabled;
    private String meetingBaseUrl = "http://meeting-service:8097";
    private String orchestratorBaseUrl = "http://notification-orchestrator:8089";
    private String tokenUrl = "http://auth-service:8088/oauth2/token";
    private String clientId = "transcript-service";
    private String clientSecret = "";
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getMeetingBaseUrl() { return meetingBaseUrl; }
    public void setMeetingBaseUrl(String value) { meetingBaseUrl = value; }
    public String getOrchestratorBaseUrl() { return orchestratorBaseUrl; }
    public void setOrchestratorBaseUrl(String value) { orchestratorBaseUrl = value; }
    public String getTokenUrl() { return tokenUrl; }
    public void setTokenUrl(String value) { tokenUrl = value; }
    public String getClientId() { return clientId; }
    public void setClientId(String value) { clientId = value; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String value) { clientSecret = value; }
}
