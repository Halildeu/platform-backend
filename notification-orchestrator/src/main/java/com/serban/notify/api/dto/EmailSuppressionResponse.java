package com.serban.notify.api.dto;

import com.serban.notify.domain.EmailSuppression;

import java.time.OffsetDateTime;

/**
 * Admin response DTO for {@link EmailSuppression} (Faz 23.8 M7 T4.3.b).
 *
 * <p>Returned by:
 * <ul>
 *   <li>POST {@code /api/v1/admin/notify/email-suppressions} (upsert)</li>
 *   <li>GET  {@code /api/v1/admin/notify/email-suppressions} (list)</li>
 * </ul>
 *
 * <p>Provenance fields ({@code lastSource}, {@code lastProvider},
 * {@code lastProviderMsgId}) help admin operators debug "where did
 * this suppression come from?" without exposing raw provider payloads.
 */
public record EmailSuppressionResponse(
    String orgId,
    String channel,
    String recipientHash,
    String recipientType,
    String reason,
    OffsetDateTime firstSeenAt,
    OffsetDateTime lastSeenAt,
    int bounceCount,
    OffsetDateTime softWindowStartedAt,
    OffsetDateTime suppressedUntil,
    String lastBounceSummaryRedacted,
    String lastSource,
    String lastProvider,
    String lastProviderMsgId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    String createdBy,
    String updatedBy
) {
    public static EmailSuppressionResponse fromEntity(EmailSuppression row) {
        return new EmailSuppressionResponse(
            row.getOrgId(),
            row.getChannel(),
            row.getRecipientHash(),
            row.getRecipientType() != null ? row.getRecipientType().name() : null,
            row.getReason() != null ? row.getReason().name() : null,
            row.getFirstSeenAt(),
            row.getLastSeenAt(),
            row.getBounceCount(),
            row.getSoftWindowStartedAt(),
            row.getSuppressedUntil(),
            row.getLastBounceSummaryRedacted(),
            row.getLastSource() != null ? row.getLastSource().name() : null,
            row.getLastProvider(),
            row.getLastProviderMsgId(),
            row.getCreatedAt(),
            row.getUpdatedAt(),
            row.getCreatedBy(),
            row.getUpdatedBy()
        );
    }
}
