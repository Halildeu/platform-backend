package com.example.ethics.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class EthicsDtos {
    private EthicsDtos() {}

    public record CreateReportRequest(
            @NotNull ReportMode mode,
            @NotBlank @Size(max=80) String category,
            @NotBlank @Size(max=240) String subject,
            @NotBlank @Size(max=16000) String description,
            @NotBlank @Size(max=12) String locale) {}
    public enum ReportMode { ANONYMOUS, CONFIDENTIAL, NAMED }
    public record CreateReportResponse(UUID receiptId, String accessSecret, Instant createdAt, String mailboxPath, boolean idempotentReplay) {}
    public record MailboxLoginRequest(@NotNull UUID receiptId, @NotBlank @Size(max=512) String accessSecret) {}
    public record MailboxSessionResponse(Instant expiresAt) {}
    public record MessageRequest(@NotBlank @Size(max=16000) String body) {}
    public record MessageResponse(UUID id, String authorType, String visibility, String body, Instant createdAt) {}
    public record CaseSummary(UUID id, String status, String assignedTo, long version, Instant createdAt, Instant updatedAt) {}
    public record CaseDetail(UUID id, String status, String assignedTo, long version, String mode, String category, String subject, String description, List<MessageResponse> messages) {}
    public record UpdateCaseRequest(@Size(max=40) String status, @Size(max=200) String assignedTo) {}
}
