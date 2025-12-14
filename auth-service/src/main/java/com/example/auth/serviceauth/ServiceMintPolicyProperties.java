package com.example.auth.serviceauth;

import java.util.HashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "dev"})
@ConfigurationProperties(prefix = "security.service-mint")
public class ServiceMintPolicyProperties {
    /** Allowed audiences (e.g., permission-service, user-service-internal) */
    private Set<String> allowedAudiences = new HashSet<>();
    /** Allowed permission strings (e.g., permissions:read) */
    private Set<String> allowedPermissions = new HashSet<>();
    /** Basic per-client rate limit (requests per minute) */
    private int rateLimitPerMinute = 120;

    public Set<String> getAllowedAudiences() { return allowedAudiences; }
    public void setAllowedAudiences(Set<String> allowedAudiences) { this.allowedAudiences = allowedAudiences; }
    public Set<String> getAllowedPermissions() { return allowedPermissions; }
    public void setAllowedPermissions(Set<String> allowedPermissions) { this.allowedPermissions = allowedPermissions; }
    public int getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(int rateLimitPerMinute) { this.rateLimitPerMinute = rateLimitPerMinute; }
}
