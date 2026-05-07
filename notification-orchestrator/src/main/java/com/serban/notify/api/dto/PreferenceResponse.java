package com.serban.notify.api.dto;

import com.serban.notify.domain.SubscriberPreference;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Subscriber preference DTO returned by the preference REST surface
 * (Faz 23.5 PR2).
 *
 * <p>Excludes raw {@code subscriber_id} and {@code org_id} from the
 * response since the caller already knows them from the JWT/header
 * context — same shape rationale as {@link InboxItemResponse}.
 *
 * <p>{@code topicKey} and {@code channel} are nullable to honor the
 * existing wildcard semantics from {@link SubscriberPreference}:
 * {@code null} means "all topics" or "all channels". Frontend should
 * render those rows as wildcard pills rather than blank values.
 */
public record PreferenceResponse(
    Long id,
    String topicKey,
    String channel,
    boolean enabled,
    Map<String, Object> quietHours,
    Integer frequencyLimitPerDay,
    boolean bypassForCritical,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static PreferenceResponse fromEntity(SubscriberPreference p) {
        return new PreferenceResponse(
            p.getId(),
            p.getTopicKey(),
            p.getChannel(),
            p.isEnabled(),
            p.getQuietHours(),
            p.getFrequencyLimitPerDay(),
            p.isBypassForCritical(),
            p.getCreatedAt(),
            p.getUpdatedAt()
        );
    }
}
