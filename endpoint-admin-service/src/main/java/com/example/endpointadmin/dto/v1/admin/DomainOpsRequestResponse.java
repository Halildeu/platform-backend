package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.domainops.DomainOpsResult;

import java.time.Instant;
import java.util.UUID;

public record DomainOpsRequestResponse(
        UUID operationId,
        UUID tenantId,
        UUID deviceId,
        String operation,
        String status,
        String reasonCode,
        long ttlSeconds,
        String requestedBy,
        Instant createdAt,
        Instant expiresAt,
        String connectorName,
        String connectorAttemptId
) {
    public static DomainOpsRequestResponse from(DomainOpsResult result) {
        return new DomainOpsRequestResponse(
                result.operationId(),
                result.tenantId(),
                result.deviceId(),
                result.operation(),
                result.status().name(),
                result.reasonCode(),
                result.ttlSeconds(),
                result.requestedBy(),
                result.createdAt(),
                result.expiresAt(),
                result.connectorName(),
                result.connectorAttemptId());
    }
}
