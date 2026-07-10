package com.example.meeting.model;

/**
 * Where a {@link MeetingAction} / {@link MeetingDecision} row came from —
 * Faz 24 (platform-ai#244). Persisted as {@code VARCHAR(32)}; V1 rows default
 * to {@link #MANUAL}.
 *
 * <p>The distinction is not cosmetic: an {@link #AI_ANALYSIS} row was extracted
 * by an LLM and has not been verified by a person, so consumers (report-service,
 * the desktop panel, the {@code action.assigned} event) must be able to tell the
 * two apart. AI rows additionally carry {@code analysis_run_id} + {@code ordinal};
 * a DB CHECK keeps that coherent.
 */
public enum MeetingItemSource {
    /** Entered by a person through the admin API. */
    MANUAL,
    /** Extracted by meeting-ai from a transcript; human-unverified. */
    AI_ANALYSIS
}
