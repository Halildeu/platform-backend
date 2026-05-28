package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.InstallPostVerification;
import com.example.endpointadmin.model.InstallPreflightDecisionRecorded;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BE-021 — REST projection of an {@code endpoint_install_audit} row.
 *
 * <p>{@code catalogItemId} is the catalog public slug (joined from the
 * catalog row on read), {@code catalogItemUuid} is the internal UUID
 * used as the audit foreign key. {@code postVerificationEvidence} and
 * {@code redactedPayload} are the same maps persisted to the
 * {@code endpoint_install_audit} JSONB columns after the
 * {@code InstallEvidencePayloadPolicy} double-redact pass.
 */
public record EndpointInstallAuditDto(
        UUID auditId,
        UUID tenantId,
        UUID deviceId,
        UUID commandId,
        String catalogItemId,
        UUID catalogItemUuid,
        String catalogPackageId,
        Long catalogRowVersion,
        InstallPreflightDecisionRecorded preflightDecision,
        Instant preflightDecisionAt,
        List<String> preflightWarnCodes,
        String actorSubject,
        String approvalSubject,
        CommandResultStatus resultStatus,
        Integer exitCode,
        Instant reportedAt,
        Instant startedAt,
        Instant finishedAt,
        InstallPostVerification postVerification,
        String detectedPackageId,
        String detectedVersion,
        Map<String, Object> postVerificationEvidence,
        Map<String, Object> redactedPayload,
        Long rowVersion,
        Instant createdAt) {
}
