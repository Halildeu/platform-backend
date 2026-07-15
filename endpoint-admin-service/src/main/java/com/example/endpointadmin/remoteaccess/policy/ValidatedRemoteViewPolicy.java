package com.example.endpointadmin.remoteaccess.policy;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** Fully re-derived source-policy facts; no digest field here is caller-trusted. */
public record ValidatedRemoteViewPolicy(
        JsonNode source,
        JsonNode baseline,
        JsonNode selectedNotice,
        UUID tenantId,
        String policyId,
        String policyVersion,
        String deploymentClass,
        String policyDigest,
        String baselineDigest,
        String legalEvidenceDigest,
        String legalEvidenceStatus,
        Instant validFrom,
        Instant validUntil,
        Instant reviewBy,
        Instant legalReviewBy) {
}
