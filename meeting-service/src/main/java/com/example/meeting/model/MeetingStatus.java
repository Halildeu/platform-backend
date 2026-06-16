package com.example.meeting.model;

/**
 * Lifecycle status of a {@link Meeting} aggregate.
 *
 * <p>Persisted as {@code VARCHAR(32)} ({@code @Enumerated(EnumType.STRING)}).
 * The V1 baseline defaults a new row to {@code SCHEDULED}.
 */
public enum MeetingStatus {
    SCHEDULED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}
