package com.example.endpointadmin.domainops;

import java.util.Map;

public record DomainOpsConnectorDispatchResult(
        DomainOpsStatus status,
        String reasonCode,
        String connectorAttemptId,
        Map<String, Object> redactedResult
) {
    public static DomainOpsConnectorDispatchResult failed(String reasonCode) {
        return new DomainOpsConnectorDispatchResult(
                DomainOpsStatus.FAILED,
                reasonCode,
                null,
                Map.of());
    }
}
