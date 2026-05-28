package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Wave-12 PR-5 — request bodies for the five {@code policy-approvals}
 * decision endpoints. Grouped in one file because each shape is a small
 * record specific to its endpoint; the {@code actor} field is the
 * deciding admin's identity (the request is HTTP-authenticated, but the
 * platform-web contract carries the actor explicitly so display name +
 * role are preserved in the history entry).
 */
public final class PolicyApprovalDecisionRequests {

    private PolicyApprovalDecisionRequests() {
    }

    public record ApproveRequest(
            @Valid @NotNull ApprovalActorDto actor,
            @Size(max = 2048) String reason
    ) {
    }

    public record RejectRequest(
            @Valid @NotNull ApprovalActorDto actor,
            @NotBlank @Size(max = 2048) String reason
    ) {
    }

    public record RequestChangesRequest(
            @Valid @NotNull ApprovalActorDto actor,
            @NotBlank @Size(max = 2048) String reason
    ) {
    }

    public record DelegateRequest(
            @Valid @NotNull ApprovalActorDto actor,
            @Valid @NotNull ApprovalActorDto delegateTo,
            @Size(max = 2048) String reason
    ) {
    }

    public record AttestRequest(
            @Valid @NotNull ApprovalActorDto actor,
            @NotBlank @Size(max = 4096) String statement,
            @NotNull Instant acceptedAt
    ) {
    }
}
