package com.example.endpointadmin.domainops;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "endpoint-admin.domain-ops.execution-connector")
public record DomainOpsExecutionConnectorProperties(
        boolean enabled,
        String mode,
        List<String> allowedCredentialRefPrefixes
) {
    public static final String DISPATCH_PLAN_ONLY_MODE = "dispatch-plan-only";

    public DomainOpsExecutionConnectorProperties {
        mode = mode == null || mode.isBlank() ? DISPATCH_PLAN_ONLY_MODE : mode.trim();
        allowedCredentialRefPrefixes = allowedCredentialRefPrefixes == null
                ? List.of()
                : List.copyOf(allowedCredentialRefPrefixes);
    }
}
