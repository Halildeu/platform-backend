package com.example.transcript.finalization;

import com.example.transcript.model.TranscriptFinalizationState;
import com.example.common.meeting.events.RecordingOutcome;
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
        observeRecordingClosed(association, finishedAt, observedAt, RecordingOutcome.FINISHED, null);
    }

    public void observeRecordingIncomplete(TranscriptSessionAssociation association,
            Instant closedAt, Instant observedAt, String reasonCode) {
        observeRecordingClosed(association, closedAt, observedAt, RecordingOutcome.INCOMPLETE, reasonCode);
    }

    private void observeRecordingClosed(TranscriptSessionAssociation association,
            Instant finishedAt, Instant observedAt, RecordingOutcome outcome, String reason) {
        outcome.validateReason(reason);
        boolean firstClosure = association.getRecordingOutcome() == RecordingOutcome.UNKNOWN;
        if (!firstClosure && (association.getRecordingOutcome() != outcome
                || !java.util.Objects.equals(association.getRecordingIncompleteReason(), reason))) {
            throw new FinalizationScopeConflictException("recording closure outcome conflicts with the stored occurrence");
        }
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
        association.setRecordingClosure(outcome, reason);
        if (association.getFinalizationState() == TranscriptFinalizationState.FINALIZED
                || association.getFinalizationState() == TranscriptFinalizationState.TIMED_OUT) {
            if (!firstClosure) {
                return;
            }
            // A previous editorial snapshot had no closure proof. Never rewrite it;
            // materialize the newly known outcome in a new immutable occurrence.
            association.setFinalizationCycleVersion(Math.max(
                    association.getFinalizationCycleVersion() + 1L,
                    association.getFinalizationVersion() + 1L));
            association.setFinishObservedAt(null);
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
            // An editorial snapshot can exist while capture is still open. The
            // first authoritative closure will start its own bounded cycle;
            // entering QUIESCING here would have no closure/observation time.
            if (association.getRecordingOutcome() == RecordingOutcome.UNKNOWN) {
                return;
            }
            // Legacy editorial-first closures stored the close time without an
            // observation marker. Use this new observation, never a guessed
            // historical time, when late content opens a valid bounded cycle.
            if (association.getFinishObservedAt() == null) {
                association.setFinishObservedAt(normalized);
            }
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
