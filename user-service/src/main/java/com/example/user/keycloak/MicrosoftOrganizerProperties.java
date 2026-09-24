package com.example.user.keycloak;

import java.net.URI;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Opt-in binding to the institution's admin-managed M365 broker. No email inference. */
@Component
@ConfigurationProperties(prefix = "teams.organizer-identity")
public class MicrosoftOrganizerProperties {
    private boolean enabled;
    private String issuer = "";
    private String tenantId = "";
    private String providerAlias = "microsoft";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getProviderAlias() { return providerAlias; }
    public void setProviderAlias(String providerAlias) { this.providerAlias = providerAlias; }

    public boolean isConfiguredFor(String realm) {
        if (!enabled || canonicalUuid(tenantId) == null || providerAlias == null
                || !providerAlias.matches("[a-zA-Z0-9_-]{1,64}")) return false;
        try {
            URI uri = URI.create(issuer);
            return "https".equals(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && uri.getPath().equals("/realms/" + realm);
        } catch (IllegalArgumentException | NullPointerException error) {
            return false;
        }
    }

    public static UUID canonicalUuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) return null;
        UUID result = UUID.fromString(value);
        return result.equals(new UUID(0, 0)) ? null : result;
    }
}
