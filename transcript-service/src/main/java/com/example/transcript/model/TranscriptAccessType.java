package com.example.transcript.model;

/**
 * KVKK Madde 12 access type recorded for every access to transcript personal
 * data. Persisted as the {@code transcript_access_audit.access_type} VARCHAR(16)
 * via {@code EnumType.STRING}.
 *
 * <ul>
 *   <li>{@link #READ}   — single segment fetched by id.</li>
 *   <li>{@link #LIST}   — a meeting's (or session's) segments listed.</li>
 *   <li>{@link #SEARCH} — segments matched by a text query (the query term
 *       itself is NEVER stored — only that a SEARCH happened + how many rows
 *       it returned).</li>
 *   <li>{@link #EXPORT} — segments exported to CSV/JSON.</li>
 * </ul>
 */
public enum TranscriptAccessType {
    READ,
    LIST,
    SEARCH,
    EXPORT
}
