package com.example.endpointadmin.dto.v1.admin;

import java.time.Instant;
import java.util.UUID;

public record RemoteViewPolicyPublicationDto(
        UUID id,
        UUID approvalId,
        UUID tenantId,
        String policyId,
        String policyVersion,
        String deploymentClass,
        String policyDigest,
        String baselineDigest,
        String legalEvidenceDigest,
        String legalEvidenceStatus,
        String supersedesPolicyDigest,
        Instant validFrom,
        Instant validUntil,
        Instant reviewBy,
        Instant legalReviewBy,
        String publishedBySubject,
        Instant publishedAt) {
}
