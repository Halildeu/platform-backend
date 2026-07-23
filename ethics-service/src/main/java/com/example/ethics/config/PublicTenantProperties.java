package com.example.ethics.config;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Faz 35 ES multi-tenant public intake — host → org mapping.
 *
 * <p>Bound from {@code ethics.tenancy.public-hosts."<host>": <org-uuid>}. Each
 * public reporter hostname (e.g. {@code speakup.acik.com} or a customer's
 * dedicated {@code ihbar.firma.com}) resolves to exactly one owning
 * organization. The map is empty by default, which preserves the existing
 * single-tenant behaviour: unmapped hosts fall back to
 * {@code ethics.public-org-id} (see {@link com.example.ethics.security.PublicTenantResolver}).
 *
 * <p>This is the public-path counterpart of the staff {@code org_id} JWT claim
 * resolved by {@code StaffContextResolver}. Adding a new customer means adding
 * one entry here plus that customer's ingress host and staff realm/org claim —
 * no data-model change, because every entity is already {@code org_id}-scoped.
 */
@ConfigurationProperties(prefix = "ethics.tenancy")
public record PublicTenantProperties(Map<String, UUID> publicHosts) {
    public PublicTenantProperties {
        publicHosts = publicHosts == null ? Map.of() : Map.copyOf(publicHosts);
    }

    /** Case-insensitive host → org view (hosts lower-cased once at binding time). */
    public Map<String, UUID> normalized() {
        return publicHosts.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                entry -> entry.getKey().toLowerCase(Locale.ROOT), Map.Entry::getValue));
    }
}
