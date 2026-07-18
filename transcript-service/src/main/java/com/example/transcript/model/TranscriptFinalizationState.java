package com.example.transcript.model;

/** Durable lifecycle of one recording-to-canonical-transcript cycle. */
public enum TranscriptFinalizationState {
    AWAITING_FINISH,
    QUIESCING,
    FINALIZED,
    TIMED_OUT
}
