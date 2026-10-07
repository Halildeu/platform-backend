package com.example.meeting.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Blank terms deliberately prevent new grants; withdrawal does not depend on grant enablement. */
@Component
@ConfigurationProperties(prefix = "meeting.bot-recording")
public class BotRecordingProperties {
    private boolean enabled;
    private String ownerBaseUrl = "http://audit-event-consumer-service:8099";
    private String tokenUrl = "http://auth-service:8088/oauth2/token";
    private String clientSecret = "";
    private String consentVersion = "";
    private String consentText = "";
    private String locale = "tr-TR";
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getOwnerBaseUrl() { return ownerBaseUrl; }
    public void setOwnerBaseUrl(String value) { ownerBaseUrl = value; }
    public String getTokenUrl() { return tokenUrl; }
    public void setTokenUrl(String value) { tokenUrl = value; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String value) { clientSecret = value; }
    public String getConsentVersion() { return consentVersion; }
    public void setConsentVersion(String value) { consentVersion = value; }
    public String getConsentText() { return consentText; }
    public void setConsentText(String value) { consentText = value; }
    public String getLocale() { return locale; }
    public void setLocale(String value) { locale = value; }
    public boolean transportConfigured() {
        return clientSecret != null && !clientSecret.isBlank() && clientSecret.length() <= 4096
                && validUrl(ownerBaseUrl, true) && validUrl(tokenUrl, false);
    }
    private static boolean validUrl(String value, boolean base) {
        try {
            URI uri = URI.create(value);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && (!base || uri.getPath().isEmpty());
        } catch (RuntimeException invalid) { return false; }
    }
}
