package com.example.ethics.security;

import com.example.ethics.config.EthicsProperties;
import com.example.ethics.config.PublicTenantProperties;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Faz 35 ES multi-tenant public intake — resolves the owning organization for a
 * public reporter request from its inbound host.
 *
 * <p>This is the public-path symmetric counterpart of {@code StaffContextResolver},
 * which resolves a staff member's org from the {@code org_id} JWT claim. A tenant
 * is a customer company: each configured public hostname maps to exactly one org
 * (see {@link PublicTenantProperties}). Hosts not present in the map fall back to
 * the default {@code ethics.public-org-id}, so the existing single-tenant cell
 * keeps behaving identically until a real second tenant is configured.
 *
 * <p>The host is never a security boundary on its own: the boundary NetworkPolicy
 * restricts ingress to the trusted reverse proxy, and every resolved org is still
 * authorization-scoped downstream. This resolver only decides WHICH tenant a
 * public report belongs to — never WHETHER the caller is allowed.
 */
@Component
public class PublicTenantResolver {
    private final Map<String, UUID> hostToOrg;
    private final UUID defaultOrg;

    public PublicTenantResolver(EthicsProperties properties, PublicTenantProperties tenants) {
        this.defaultOrg = properties.publicOrgId();
        this.hostToOrg = tenants.normalized();
    }

    /**
     * Resolve the org owning a public-intake request that arrived on {@code host}
     * (the reverse-proxy Host header, threaded through the service as the
     * {@code channel} argument). Blank or unmapped hosts resolve to the default org.
     */
    public UUID resolve(String host) {
        if (host == null) {
            return defaultOrg;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return defaultOrg;
        }
        return hostToOrg.getOrDefault(normalized, defaultOrg);
    }
}
