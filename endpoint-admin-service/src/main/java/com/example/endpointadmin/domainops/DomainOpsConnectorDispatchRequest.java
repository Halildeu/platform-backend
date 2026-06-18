package com.example.endpointadmin.domainops;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DomainOpsConnectorDispatchRequest(
        UUID requestId,
        UUID tenantId,
        UUID deviceId,
        String hostname,
        String domainName,
        DomainOpsOperation operation,
        Map<String, Object> operationPayload,
        String credentialRef,
        Instant expiresAt,
        String reason
) {
}
