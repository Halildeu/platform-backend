package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.PolicyRiskTier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Creates a four-eyes DELETE approval bound to one immutable publication. */
public record RemoteViewPolicyRevocationProposalRequest(
        @NotNull UUID publicationId,
        @NotBlank @Size(max = 255) String title,
        @Valid @NotNull ApprovalActorDto proposer,
        @NotBlank @Size(max = 2048) String reason,
        @Size(max = 32) List<@Size(max = 512) String> evidenceRefs,
        @Valid @Size(min = 1, max = 16) List<@NotNull ApprovalActorDto> currentApprovers,
        Instant deadline,
        @NotNull PolicyRiskTier riskTier) {
}
