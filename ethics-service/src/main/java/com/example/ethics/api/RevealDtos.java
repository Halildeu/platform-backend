package com.example.ethics.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RevealDtos {
    private RevealDtos() {}

    public record RevealSubmitRequest(
            @NotNull UUID caseId,
            @NotBlank @Size(max=200) String requesterName,
            @NotBlank @Size(max=40) @Pattern(regexp="[A-Z0-9_]+") String legalBasis,
            @NotBlank @Size(max=400) String legalAuthority,
            @NotBlank @Size(max=200) String referenceNumber,
            @NotBlank @Size(max=4000) String justification) {}

    public record RevealApproveRequest(
            @NotBlank @Size(max=200) String approverName,
            @NotBlank @Size(max=80) String approverRole) {}

    public record RevealRejectRequest(
            @NotBlank @Size(max=4000) String reason) {}

    public record RevealResponse(
            UUID id, UUID caseId, String status,
            String legalBasis, String legalAuthority, String referenceNumber,
            String requesterSubject, String requesterName, Instant requestedAt,
            String firstApproverSubject, String firstApproverName, Instant firstApprovedAt,
            String secondApproverSubject, String secondApproverName, Instant secondApprovedAt,
            String rejectedBySubject, String rejectionReason, Instant rejectedAt,
            String executedBySubject, Instant executedAt) {}

    public record RevealPayloadResponse(
            UUID requestId, UUID caseId, String mode, String category,
            String subject, String description, String locale, String noticeVersion,
            String caseStatus, Instant caseCreatedAt, Instant caseUpdatedAt,
            List<RevealMessageEntry> messages, Instant executedAt) {}

    public record RevealMessageEntry(UUID id, String authorType, String visibility,
            String body, Instant createdAt) {}

    public record RevealAuditEntry(UUID id, String eventType, String actorSubject,
            String actorRole, String payload, Instant createdAt) {}
}
