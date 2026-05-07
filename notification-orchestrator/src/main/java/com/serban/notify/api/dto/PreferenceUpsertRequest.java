package com.serban.notify.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Upsert request body for the subscriber preference REST surface
 * (Faz 23.5 PR2).
 *
 * <p>Identifies the (topicKey, channel) tuple the caller wants to set;
 * both fields are nullable to mirror the wildcard semantics in the
 * underlying {@link com.serban.notify.domain.SubscriberPreference}
 * table. The service layer enforces uniqueness via the existing
 * {@code (org_id, subscriber_id, topic_key, channel)} composite key —
 * a second PUT for the same tuple updates the existing row instead of
 * inserting a duplicate.
 *
 * <p>Field semantics:
 * <ul>
 *   <li>{@code enabled} — controls allow/deny. {@code false} means the
 *       channel/topic is muted for the subscriber. Default-allow rule
 *       applies if no row exists for the tuple.</li>
 *   <li>{@code quietHours} — optional JSON object describing time
 *       windows during which delivery is suppressed; shape is opaque
 *       to the API and consumed by
 *       {@link com.serban.notify.eligibility.DeliveryEligibilityService}.</li>
 *   <li>{@code frequencyLimitPerDay} — optional cap for "no more than
 *       N notifications/day" UX. Eligibility service enforces.</li>
 *   <li>{@code bypassForCritical} — when {@code true}, severity=critical
 *       intents bypass the disabled flag. Default {@code true}; user
 *       can opt out for full silence.</li>
 * </ul>
 */
public record PreferenceUpsertRequest(
    @Size(max = 128)
    String topicKey,
    @Size(max = 32)
    String channel,
    boolean enabled,
    Map<String, Object> quietHours,
    @Min(value = 0, message = "frequencyLimitPerDay must be >= 0 (0 disables limit)")
    Integer frequencyLimitPerDay,
    Boolean bypassForCritical
) {}
