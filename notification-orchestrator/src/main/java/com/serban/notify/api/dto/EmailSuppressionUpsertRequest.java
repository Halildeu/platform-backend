package com.serban.notify.api.dto;

import com.serban.notify.domain.EmailSuppression;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Admin upsert request for {@link EmailSuppression} (Faz 23.8 M7
 * T4.3.b).
 *
 * <p>POST {@code /api/v1/admin/notify/email-suppressions} accepts this
 * payload; service applies soft-bounce threshold logic
 * ({@code EmailSuppressionService.upsert}) and returns the resulting
 * row.
 *
 * <p>{@code recipientHash} is the canonical SHA-256 of the normalized
 * recipient email — caller (admin tool) is responsible for hashing;
 * raw email never appears in this API.
 */
public record EmailSuppressionUpsertRequest(
    @NotBlank @Size(max = 128) String recipientHash,
    @NotNull EmailSuppression.Reason reason,
    EmailSuppression.RecipientType recipientType,
    @Size(max = 256) String summaryRedacted,
    @Size(max = 64) String provider
) {}
