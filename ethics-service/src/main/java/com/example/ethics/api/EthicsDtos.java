package com.example.ethics.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class EthicsDtos {
    private EthicsDtos() {}

    public record CreateReportRequest(
            @NotNull ReportMode mode,
            @NotNull ReportCategory category,
            @NotBlank @Size(max=240) String subject,
            @NotBlank @Size(max=16000) String description,
            @NotBlank @Size(max=12) String locale,
            @NotBlank @Size(min=43,max=128) @Pattern(regexp="[A-Za-z0-9_-]+") String accessSecret,
            @NotBlank @Size(max=80) @Pattern(regexp="[A-Za-z0-9._-]+") String noticeVersion) {}
    public enum ReportMode { ANONYMOUS, CONFIDENTIAL, NAMED }
    public enum ReportCategory { WORKPLACE_CONDUCT, FRAUD_CORRUPTION, HARASSMENT_DISCRIMINATION, OTHER }
    public record CreateReportResponse(UUID receiptId, Instant createdAt, String mailboxPath, boolean idempotentReplay) {}
    public record MailboxLoginRequest(@NotNull UUID receiptId, @NotBlank @Size(max=512) String accessSecret) {}
    public record MailboxSessionResponse(Instant expiresAt) {}
    public record MessageRequest(@NotBlank @Size(max=16000) String body) {}
    public record MessageResponse(UUID id, String authorType, String visibility, String body, Instant createdAt) {}
    public record MailboxViewResponse(String status, List<MessageResponse> messages) {}
    public record CaseSummary(UUID id, String status, String assignedTo, long version, Instant createdAt, Instant updatedAt,
                              Instant acknowledgedAt, String outcome, Instant closedAt) {}
    public record CaseDetail(UUID id, String status, String assignedTo, long version, String mode, String category, String subject, String description, List<MessageResponse> messages,
                             Instant acknowledgedAt, String outcome, Instant closedAt) {}
    /**
     * ES-301A / ES-301B. {@code outcome} is required when closing and refused otherwise;
     * {@code reason} is required only when reopening a closed case. Neither is a free-form
     * annotation — a conclusion with no finding, or a reopening with no stated cause, leaves
     * a record that cannot be read back years later.
     *
     * <p>{@code closingMessage} is what the reporter is actually told, and closing does not
     * happen without it. It is staff-authored prose rather than a rendering of
     * {@code outcome}: the internal finding is a workflow value, and what a person outside
     * the organisation should be told about their report is a judgement someone has to make.
     */
    public record UpdateCaseRequest(@Size(max=40) String status, @Size(max=200) String assignedTo,
                                    @Size(max=40) String outcome, @Size(max=500) String reason,
                                    @Size(max=16000) String closingMessage) {}
    /**
     * ES-203 / B+ slice 1. The subject is a Keycloak subject UUID — the same identity the policy
     * engine uses. The pattern refuses anything that is not one, so a display name or a free-text
     * label cannot arrive here and be mistaken for a principal.
     */
    public record AddParticipantRequest(
            @NotBlank @Size(max=128) @Pattern(regexp="v[0-9]+\\.[A-Za-z0-9_-]+") String handle,
            @NotBlank @Size(max=32) String role) {}
    /**
     * ES-203/D — the listing crosses the same boundary the assignment does.
     *
     * <p>ES-203/C adds {@code displayName}: a pass-through from the user
     * directory, resolved at read time and stored nowhere in this service. It
     * is {@code null} when the directory cannot answer — this is a display
     * surface, and a missing name degrades the view without blocking it.
     */
    public record CaseParticipantView(String handle, String displayName, String role,
                                      java.time.Instant addedAt) {}
    /**
     * ES-203/C — one selectable person in the assignment picker.
     *
     * <p>The handle is what the client sends back; the name is what the human
     * reads. Names are not unique, which is why both travel together: the UI
     * must disambiguate duplicates (it shows a handle-derived discriminator)
     * rather than letting two "Ayşe Yılmaz" rows collapse into one choice.
     */
    public record AssignableStaffEntry(String handle, String displayName) {}
}
