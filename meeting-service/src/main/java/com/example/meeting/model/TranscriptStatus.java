package com.example.meeting.model;

/**
 * Transcript-processing state of a {@link MeetingSession}. Persisted as
 * {@code VARCHAR(32)}. V1 baseline defaults a new row to {@code PENDING}.
 */
public enum TranscriptStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
