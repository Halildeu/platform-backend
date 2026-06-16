package com.example.transcript.model;

/**
 * Lifecycle status of a transcript segment.
 *
 * <ul>
 *   <li>{@link #DRAFT}     — raw ASR output ({@code text_draft}); not yet
 *       human-reviewed.</li>
 *   <li>{@link #FINALIZED} — corrected/approved text present
 *       ({@code text_final}).</li>
 *   <li>{@link #REDACTED}  — content removed for KVKK/privacy reasons (the row
 *       is kept for audit linkage but its text is cleared by the caller).</li>
 * </ul>
 */
public enum TranscriptSegmentStatus {
    DRAFT,
    FINALIZED,
    REDACTED
}
