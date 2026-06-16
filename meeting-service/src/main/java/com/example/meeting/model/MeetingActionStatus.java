package com.example.meeting.model;

/**
 * Status of a {@link MeetingAction} item. Persisted as {@code VARCHAR(32)}.
 * V1 baseline defaults a new row to {@code OPEN}.
 */
public enum MeetingActionStatus {
    OPEN,
    IN_PROGRESS,
    DONE,
    CANCELLED
}
