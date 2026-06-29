package com.example.endpointadmin.service.rolloutfailure;

import java.util.UUID;

/** A committed COLLECT_INVENTORY result carried a validated securityNetwork block. */
public record SecurityNetworkBlockSubmittedEvent(UUID tenantId, UUID deviceId, UUID commandResultId) {
}
