package com.example.user.notify;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the #734 admin "new M365 user awaiting activation" email.
 *
 * <p>Prefix {@code user.pending-activation-notification}. Disabled by default
 * (opt-in per environment) so the feature only fires where the recipient + the
 * notification-orchestrator internal path are configured.
 */
@ConfigurationProperties(prefix = "user.pending-activation-notification")
public class PendingActivationNotificationProperties {

    /** Master switch — off by default. */
    private boolean enabled = false;
    /** notification-orchestrator base URL (in-cluster). */
    private String baseUrl = "http://notification-orchestrator:8089";
    /** Admin recipient mailbox (external recipient). */
    private String adminEmail = "";
    /** Org partition/idempotency selector for the intent. */
    private String orgId = "platform-system";
    /** Template locale. */
    private String locale = "tr-TR";
    /** Service-token audience to mint for the internal submit. */
    private String tokenAudience = "notification-orchestrator";
    /** Service-token permissions to request. */
    private List<String> tokenPermissions = List.of("notify:intents:system");
    /** HTTP timeout (ms) for the best-effort submit. */
    private long timeoutMillis = 2000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getAdminEmail() { return adminEmail; }
    public void setAdminEmail(String adminEmail) { this.adminEmail = adminEmail; }
    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public String getTokenAudience() { return tokenAudience; }
    public void setTokenAudience(String tokenAudience) { this.tokenAudience = tokenAudience; }
    public List<String> getTokenPermissions() { return tokenPermissions; }
    public void setTokenPermissions(List<String> tokenPermissions) { this.tokenPermissions = tokenPermissions; }
    public long getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(long timeoutMillis) { this.timeoutMillis = timeoutMillis; }
}
