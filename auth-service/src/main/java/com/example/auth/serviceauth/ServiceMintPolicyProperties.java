package com.example.auth.serviceauth;

import java.util.HashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "security.service-mint")
public class ServiceMintPolicyProperties {
    /** Allowed audiences (e.g., permission-service, user-service-internal) */
    private Set<String> allowedAudiences = new HashSet<>();
    /** Allowed permission strings (e.g., permissions:read) */
    private Set<String> allowedPermissions = new HashSet<>();
    /** Basic per-client rate limit (requests per minute) */
    private int rateLimitPerMinute = 120;
    /** Shared invalid-client budget; one bucket avoids client-id enumeration and unbounded keys. */
    private int failedAuthRateLimitPerMinute = 30;

    public Set<String> getAllowedAudiences() { return allowedAudiences; }
    public void setAllowedAudiences(Set<String> allowedAudiences) { this.allowedAudiences = allowedAudiences; }
    public Set<String> getAllowedPermissions() { return allowedPermissions; }
    public void setAllowedPermissions(Set<String> allowedPermissions) { this.allowedPermissions = allowedPermissions; }
    public int getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(int rateLimitPerMinute) { this.rateLimitPerMinute = rateLimitPerMinute; }
    public int getFailedAuthRateLimitPerMinute() { return failedAuthRateLimitPerMinute; }
    public void setFailedAuthRateLimitPerMinute(int failedAuthRateLimitPerMinute) {
        this.failedAuthRateLimitPerMinute = failedAuthRateLimitPerMinute;
    }
}
