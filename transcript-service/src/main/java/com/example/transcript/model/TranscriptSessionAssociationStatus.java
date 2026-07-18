package com.example.transcript.model;

/** Lifecycle of a transcript-local canonical meeting-session projection. */
public enum TranscriptSessionAssociationStatus {
    PENDING,
    RESOLVING,
    RESOLVED,
    DEAD
}
