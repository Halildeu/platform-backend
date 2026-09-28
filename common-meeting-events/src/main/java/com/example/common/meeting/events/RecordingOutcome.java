package com.example.common.meeting.events;

/** Canonical closure provenance; UNKNOWN never implies successful or complete capture. */
public enum RecordingOutcome {
    UNKNOWN, FINISHED, INCOMPLETE;

    public void validateReason(String reason) {
        if (this == INCOMPLETE
                ? !"CLOSURE_UNCONFIRMED".equals(reason)
                : reason != null) {
            throw new IllegalArgumentException("RECORDING_OUTCOME_REASON_INVALID");
        }
    }
}
