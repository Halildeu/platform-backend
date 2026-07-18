package com.example.transcript.finalization;

import com.example.transcript.model.TranscriptFinalizationState;
import com.example.transcript.model.TranscriptSessionAssociation;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** Pure transition policy for restart-safe recording finalization. */
@Component
public class TranscriptFinalizationStateMachine {

    private final Duration quiescence;
    private final Duration minWait;
    private final Duration maxWait;

    public TranscriptFinalizationStateMachine(TranscriptFinalizationProperties properties) {
        quiescence = properties.getTiming().getQuiescence();
        minWait = properties.getTiming().getMinWait();
        maxWait = properties.getTiming().getMaxWait();
    }

    public void observeRecordingFinished(
            TranscriptSessionAssociation association,
            Instant finishedAt,
            Instant observedAt) {
        Instant normalizedFinishedAt = micros(finishedAt);
        Instant normalizedObservedAt = micros(observedAt);
        if (association.getRecordingFinishedAt() != null
                && !association.getRecordingFinishedAt().equals(normalizedFinishedAt)) {
            throw new FinalizationScopeConflictException(
                    "recording finished timestamp conflicts with the stored occurrence");
        }
        if (association.getRecordingFinishedAt() == null) {
            association.setRecordingFinishedAt(normalizedFinishedAt);
        }
        if (association.getFinalizationState() == TranscriptFinalizationState.FINALIZED
                || association.getFinalizationState() == TranscriptFinalizationState.TIMED_OUT) {
            return;
        }
        if (association.getFinishObservedAt() == null) {
            association.setFinishObservedAt(normalizedObservedAt);
            association.setMinWaitAt(normalizedObservedAt.plus(minWait));
            association.setMaxWaitAt(normalizedObservedAt.plus(maxWait));
        }
        if (association.getFinalizationCycleVersion() == 0) {
            association.setFinalizationCycleVersion(
                    Math.max(1L, association.getFinalizationVersion() + 1L));
        }
        association.setFinalizationState(TranscriptFinalizationState.QUIESCING);
        association.setFinalizationErrorCode(null);
        recomputeDue(association);
    }

    public void recordDistinctContent(
            TranscriptSessionAssociation association,
            Instant changedAt) {
        Instant normalized = micros(changedAt);
        if (association.getLastContentChangedAt() == null
                || association.getLastContentChangedAt().isBefore(normalized)) {
            association.setLastContentChangedAt(normalized);
        }

        TranscriptFinalizationState state = association.getFinalizationState();
        if (state == TranscriptFinalizationState.FINALIZED
                || state == TranscriptFinalizationState.TIMED_OUT) {
            association.setFinalizationCycleVersion(Math.max(
                    association.getFinalizationCycleVersion() + 1L,
                    association.getFinalizationVersion() + 1L));
            association.setFinalizationState(TranscriptFinalizationState.QUIESCING);
            association.setMinWaitAt(normalized);
            association.setMaxWaitAt(normalized.plus(maxWait));
            association.setFinalizationErrorCode(null);
        }
        if (association.getFinalizationState() == TranscriptFinalizationState.QUIESCING) {
            recomputeDue(association);
        }
    }

    public void markFinalized(TranscriptSessionAssociation association) {
        association.setFinalizationVersion(association.getFinalizationCycleVersion());
        association.setFinalizationState(TranscriptFinalizationState.FINALIZED);
        association.setQuiescenceDueAt(null);
        association.setFinalizationErrorCode(null);
    }

    public void markTimedOut(TranscriptSessionAssociation association, String reasonCode) {
        association.setFinalizationState(TranscriptFinalizationState.TIMED_OUT);
        association.setQuiescenceDueAt(null);
        association.setFinalizationErrorCode(reasonCode);
    }

    private void recomputeDue(TranscriptSessionAssociation association) {
        Instant due = association.getMinWaitAt();
        if (association.getLastContentChangedAt() != null) {
            Instant contentDue = association.getLastContentChangedAt().plus(quiescence);
            if (contentDue.isAfter(due)) {
                due = contentDue;
            }
        }
        if (due.isAfter(association.getMaxWaitAt())) {
            due = association.getMaxWaitAt();
        }
        association.setQuiescenceDueAt(due);
    }

    private Instant micros(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    public static class FinalizationScopeConflictException extends IllegalStateException {
        public FinalizationScopeConflictException(String message) { super(message); }
    }
}
