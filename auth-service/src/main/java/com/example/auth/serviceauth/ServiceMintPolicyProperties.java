package com.example.auth.serviceauth;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
    /**
     * Optional per-permission client allowlist (ai#244 AI-1).
     *
     * <p>The global {@link #allowedPermissions} set only gates whether a permission is
     * mintable <em>at all</em>; it does not bind a permission to a specific caller, so any
     * authenticated service-client can request any allowed permission. For sensitive
     * write scopes (e.g. {@code meeting:analysis-result:write}, which guards
     * meeting-service's single-writer ingestion authority) that global model is a privilege
     * escalation: a compromised or unrelated client could mint a token and forge results.
     *
     * <p>When a permission appears as a key here, <strong>only</strong> the listed clients
     * may mint it. Permissions absent from this map keep the legacy global behaviour
     * (backward-compat), so this narrows the surface without touching existing callers.
     */
    private Map<String, Set<String>> permissionClientBindings = new HashMap<>();
    /** Basic per-client rate limit (requests per minute) */
    private int rateLimitPerMinute = 120;

    public Set<String> getAllowedAudiences() { return allowedAudiences; }
    public void setAllowedAudiences(Set<String> allowedAudiences) { this.allowedAudiences = allowedAudiences; }
    public Set<String> getAllowedPermissions() { return allowedPermissions; }
    public void setAllowedPermissions(Set<String> allowedPermissions) { this.allowedPermissions = allowedPermissions; }
    public Map<String, Set<String>> getPermissionClientBindings() { return permissionClientBindings; }
    public void setPermissionClientBindings(Map<String, Set<String>> permissionClientBindings) {
        this.permissionClientBindings = permissionClientBindings;
    }
    public int getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(int rateLimitPerMinute) { this.rateLimitPerMinute = rateLimitPerMinute; }
}
