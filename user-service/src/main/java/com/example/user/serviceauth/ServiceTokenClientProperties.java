package com.example.user.serviceauth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "security.service-token.client")
public class ServiceTokenClientProperties {
    /** Token mint endpoint URL (auth-service) */
    private String tokenUrl = "http://localhost:8088/oauth2/token";
    private String clientId = "user-service";
    private String clientSecret = "dev-secret";
    private boolean enabled = true;

    public String getTokenUrl() { return tokenUrl; }
    public void setTokenUrl(String tokenUrl) { this.tokenUrl = tokenUrl; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}

