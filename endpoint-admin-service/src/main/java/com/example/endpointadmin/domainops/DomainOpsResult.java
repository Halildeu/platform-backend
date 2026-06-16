package com.example.endpointadmin.domainops;

import java.time.Instant;
import java.util.UUID;

public record DomainOpsResult(
        UUID operationId,
        UUID tenantId,
        UUID deviceId,
        String operation,
        DomainOpsStatus status,
        String reasonCode,
        long ttlSeconds,
        String requestedBy,
        Instant createdAt
) {
}
