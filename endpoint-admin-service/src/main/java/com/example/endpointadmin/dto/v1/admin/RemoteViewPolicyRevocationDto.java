package com.example.endpointadmin.dto.v1.admin;

import java.time.Instant;
import java.util.UUID;

public record RemoteViewPolicyRevocationDto(
        UUID id,
        UUID publicationId,
        UUID approvalId,
        UUID tenantId,
        String policyDigest,
        String reason,
        String revokedBySubject,
        Instant revokedAt) {
}
