package com.example.ethics.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class EvidenceDtos {
    private EvidenceDtos() {}

    public record EvidenceDeclarationRequest(
            @NotBlank @Size(max = 80) String mediaType,
            @Min(1) @Max(26_214_400) long size,
            @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String sha256) {}

    public record EvidenceDeclarationResponse(
            UUID attachmentId,
            String state,
            String uploadPath,
            String uploadCapability,
            Instant uploadExpiresAt,
            boolean idempotentReplay) {}

    public record EvidenceStatusResponse(
            UUID attachmentId,
            String state,
            String mediaType,
            long size,
            String failureCode,
            Instant createdAt,
            Instant updatedAt) {}

    public record StaffEvidenceResponse(
            UUID attachmentId,
            String state,
            String mediaType,
            Long size,
            Instant createdAt,
            boolean derivativeAvailable) {}
}
