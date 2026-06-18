package com.example.endpointadmin.domainops;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public enum DomainOpsOperation {
    DOMAIN_SECURE_CHANNEL_VERIFY,
    GPO_FORCE_REFRESH,
    CERT_AUTOENROLL_PULSE,
    ENDPOINT_AGENT_GPO_MSI_DEPLOYMENT,
    ENDPOINT_AGENT_ROLLOUT_COLLECTOR;

    // Deliberately explicit opt-in: adding an enum constant must not
    // automatically make a new domain operation broker-dispatchable.
    private static final Set<DomainOpsOperation> SAFE_PILOT_OPERATIONS = Set.of(
            DOMAIN_SECURE_CHANNEL_VERIFY,
            GPO_FORCE_REFRESH,
            CERT_AUTOENROLL_PULSE,
            ENDPOINT_AGENT_GPO_MSI_DEPLOYMENT,
            ENDPOINT_AGENT_ROLLOUT_COLLECTOR);

    public static Optional<DomainOpsOperation> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim()
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        try {
            DomainOpsOperation op = DomainOpsOperation.valueOf(normalized);
            return SAFE_PILOT_OPERATIONS.contains(op) ? Optional.of(op) : Optional.empty();
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
