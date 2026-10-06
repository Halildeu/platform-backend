package com.example.transcript.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.transcript.model.TranscriptFinalizationState;
import com.example.transcript.model.TranscriptSessionAssociation;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.example.common.meeting.events.RecordingOutcome;

class TranscriptFinalizationStateMachineTest {

    private final TranscriptFinalizationStateMachine stateMachine =
            new TranscriptFinalizationStateMachine(new TranscriptFinalizationProperties());

    @Test
    void defaultTimingContractIsExactlyPt6mPt1mPt15m() {
        TranscriptFinalizationProperties properties = new TranscriptFinalizationProperties();

        assertThat(properties.getTiming().getMinWait()).isEqualTo(Duration.ofMinutes(6));
        assertThat(properties.getTiming().getQuiescence()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.getTiming().getMaxWait()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void recordingFinishedStartsExactPt6mToPt15mBoundedCycle() {
        TranscriptSessionAssociation association = association(TranscriptFinalizationState.AWAITING_FINISH, 0, 0);
        Instant finishedAt = Instant.parse("2026-07-17T10:00:00.123456789Z");
        Instant observedAt = Instant.parse("2026-07-17T10:00:10.987654321Z");

        stateMachine.observeRecordingFinished(association, finishedAt, observedAt);

        assertThat(association.getRecordingFinishedAt())
                .isEqualTo(Instant.parse("2026-07-17T10:00:00.123456Z"));
        assertThat(association.getFinishObservedAt())
                .isEqualTo(Instant.parse("2026-07-17T10:00:10.987654Z"));
        assertThat(association.getFinalizationState()).isEqualTo(TranscriptFinalizationState.QUIESCING);
        assertThat(association.getFinalizationCycleVersion()).isEqualTo(1);
        assertThat(association.getMinWaitAt())
                .isEqualTo(observedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                        .plus(Duration.ofMinutes(6)));
        assertThat(association.getMaxWaitAt())
                .isEqualTo(observedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                        .plus(Duration.ofMinutes(15)));
        assertThat(association.getQuiescenceDueAt()).isEqualTo(association.getMinWaitAt());
    }

    @Test
    void lateContentAfterMinWaitRequiresExactlyPt1mOfQuiescence() {
        TranscriptSessionAssociation association = association(TranscriptFinalizationState.AWAITING_FINISH, 0, 0);
        Instant observedAt = Instant.parse("2026-07-17T10:00:00Z");
        stateMachine.observeRecordingFinished(association, observedAt, observedAt);
        Instant lateContentAt = observedAt.plus(Duration.ofMinutes(7));

        stateMachine.recordDistinctContent(association, lateContentAt);

        assertThat(association.getMinWaitAt()).isEqualTo(observedAt.plus(Duration.ofMinutes(6)));
        assertThat(association.getQuiescenceDueAt()).isEqualTo(lateContentAt.plus(Duration.ofMinutes(1)));
        assertThat(association.getMaxWaitAt()).isEqualTo(observedAt.plus(Duration.ofMinutes(15)));
    }

    @Test
    void lateContentCannotExtendTheExactPt15mCap() {
        TranscriptSessionAssociation association = association(TranscriptFinalizationState.AWAITING_FINISH, 0, 0);
        Instant observedAt = Instant.parse("2026-07-17T10:00:00Z");
        stateMachine.observeRecordingFinished(association, observedAt, observedAt);

        Instant lateContentAt = observedAt.plus(Duration.ofMinutes(15)).plusNanos(1_000);
        stateMachine.recordDistinctContent(association, lateContentAt);

        assertThat(association.getLastContentChangedAt()).isEqualTo(lateContentAt);
        assertThat(association.getQuiescenceDueAt()).isEqualTo(observedAt.plus(Duration.ofMinutes(15)));
        assertThat(association.getMaxWaitAt()).isEqualTo(observedAt.plus(Duration.ofMinutes(15)));
    }

    @Test
    void distinctContentAfterFinalizationOpensNextRevisionCycle() {
        TranscriptSessionAssociation association = association(TranscriptFinalizationState.FINALIZED, 3, 3);
        association.setRecordingClosure(RecordingOutcome.FINISHED, null);
        association.setRecordingFinishedAt(Instant.parse("2026-07-17T10:00:00Z"));
        association.setFinishObservedAt(Instant.parse("2026-07-17T10:00:01Z"));
        Instant changedAt = Instant.parse("2026-07-17T11:00:00.123456789Z");

        stateMachine.recordDistinctContent(association, changedAt);

        assertThat(association.getFinalizationState()).isEqualTo(TranscriptFinalizationState.QUIESCING);
        assertThat(association.getFinalizationCycleVersion()).isEqualTo(4);
        assertThat(association.getMinWaitAt()).isEqualTo(Instant.parse("2026-07-17T11:00:00.123456Z"));
        assertThat(association.getQuiescenceDueAt())
                .isEqualTo(Instant.parse("2026-07-17T11:01:00.123456Z"));
        assertThat(association.getMaxWaitAt())
                .isEqualTo(Instant.parse("2026-07-17T11:15:00.123456Z"));
    }

    @Test
    void sameFinishReplayIsIdempotentButDivergentTimestampFailsClosed() {
        TranscriptSessionAssociation association = association(TranscriptFinalizationState.AWAITING_FINISH, 0, 0);
        Instant finishedAt = Instant.parse("2026-07-17T10:00:00Z");
        stateMachine.observeRecordingFinished(association, finishedAt, finishedAt.plusSeconds(1));

        stateMachine.observeRecordingFinished(association, finishedAt, finishedAt.plusSeconds(5));

        assertThat(association.getFinishObservedAt()).isEqualTo(finishedAt.plusSeconds(1));
        assertThatThrownBy(() -> stateMachine.observeRecordingFinished(
                association, finishedAt.plusSeconds(1), finishedAt.plusSeconds(6)))
                .isInstanceOf(TranscriptFinalizationStateMachine.FinalizationScopeConflictException.class);
    }

    @ParameterizedTest
    @EnumSource(value = RecordingOutcome.class, names = {"FINISHED", "INCOMPLETE"})
    void firstClosureAfterEditorialSnapshotStartsFreshVersionWithoutRewritingPriorVersion(RecordingOutcome outcome) {
        var association = association(TranscriptFinalizationState.FINALIZED, 3, 3);
        Instant closedAt = Instant.parse("2026-07-17T10:00:00Z");
        Instant observedAt = closedAt.plusSeconds(30);
        observe(association, outcome, closedAt, observedAt);

        assertThat(association.getRecordingOutcome()).isEqualTo(outcome);
        assertThat(association.getFinalizationVersion()).isEqualTo(3);
        assertThat(association.getFinalizationCycleVersion()).isEqualTo(4);
        assertThat(association.getFinalizationState()).isEqualTo(TranscriptFinalizationState.QUIESCING);
        assertThat(association.getMinWaitAt()).isEqualTo(observedAt.plusSeconds(360));
        assertThat(association.getMaxWaitAt()).isEqualTo(observedAt.plusSeconds(900));
        observe(association, outcome, closedAt, observedAt.plusSeconds(120));
        assertThat(association.getFinishObservedAt()).isEqualTo(observedAt);
        assertThat(association.getFinalizationCycleVersion()).isEqualTo(4);
        assertThat(association.getMaxWaitAt()).isEqualTo(observedAt.plusSeconds(900));
    }

    @ParameterizedTest
    @EnumSource(value = RecordingOutcome.class, names = {"FINISHED", "INCOMPLETE"})
    void mutuallyExclusiveClosureCannotReplaceStoredOutcome(RecordingOutcome first) {
        var association = association(TranscriptFinalizationState.AWAITING_FINISH, 0, 0);
        Instant time = Instant.parse("2026-07-17T10:00:00Z");
        observe(association, first, time, time);
        RecordingOutcome other = first == RecordingOutcome.FINISHED
                ? RecordingOutcome.INCOMPLETE : RecordingOutcome.FINISHED;
        assertThatThrownBy(() -> observe(association, other, time, time.plusSeconds(1)))
                .isInstanceOf(TranscriptFinalizationStateMachine.FinalizationScopeConflictException.class);
        assertThat(association.getRecordingOutcome()).isEqualTo(first);
        assertThat(association.getFinishObservedAt()).isEqualTo(time);
    }

    @Test
    void editorialContentBeforeClosureDoesNotEnterInvalidQuiescenceState() {
        var association = association(TranscriptFinalizationState.FINALIZED, 2, 2);
        Instant changedAt = Instant.parse("2026-07-17T10:00:00Z");
        stateMachine.recordDistinctContent(association, changedAt);
        assertThat(association.getLastContentChangedAt()).isEqualTo(changedAt);
        assertThat(association.getFinalizationState()).isEqualTo(TranscriptFinalizationState.FINALIZED);
        assertThat(association.getFinalizationCycleVersion()).isEqualTo(2);
        assertThat(association.getQuiescenceDueAt()).isNull();
        observe(association, RecordingOutcome.INCOMPLETE, changedAt.plusSeconds(1), changedAt.plusSeconds(2));
        assertThat(association.getFinalizationCycleVersion()).isEqualTo(3);
    }

    @ParameterizedTest
    @EnumSource(value = TranscriptFinalizationState.class, names = {"FINALIZED", "TIMED_OUT"})
    void incompleteReplayDoesNotReopenTerminalCycleButLateContentDoes(TranscriptFinalizationState terminal) {
        var association = association(TranscriptFinalizationState.AWAITING_FINISH, 0, 0);
        Instant time = Instant.parse("2026-07-17T10:00:00Z");
        observe(association, RecordingOutcome.INCOMPLETE, time, time);
        if (terminal == TranscriptFinalizationState.FINALIZED) stateMachine.markFinalized(association);
        else stateMachine.markTimedOut(association, "NO_VALID_SEGMENTS_BEFORE_DEADLINE");
        observe(association, RecordingOutcome.INCOMPLETE, time, time.plusSeconds(1000));
        assertThat(association.getFinalizationState()).isEqualTo(terminal);
        assertThat(association.getFinalizationCycleVersion()).isEqualTo(1);
        assertThat(association.getQuiescenceDueAt()).isNull();
        stateMachine.recordDistinctContent(association, time.plusSeconds(1200));
        assertThat(association.getFinalizationState()).isEqualTo(TranscriptFinalizationState.QUIESCING);
        assertThat(association.getFinalizationCycleVersion()).isEqualTo(2);
        assertThat(association.getRecordingOutcome()).isEqualTo(RecordingOutcome.INCOMPLETE);
        assertThat(association.getRecordingIncompleteReason()).isEqualTo("CLOSURE_UNCONFIRMED");
        assertThat(association.getQuiescenceDueAt()).isEqualTo(time.plusSeconds(1260));
    }

    @Test
    void lateContentRepairsLegacyMissingObservationMarkerWithoutInventingHistoricalTime() {
        var association = association(TranscriptFinalizationState.FINALIZED, 3, 3);
        Instant closedAt = Instant.parse("2026-07-17T10:00:00Z");
        association.setRecordingClosure(RecordingOutcome.FINISHED, null);
        association.setRecordingFinishedAt(closedAt);
        Instant changedAt = closedAt.plusSeconds(3600);
        stateMachine.recordDistinctContent(association, changedAt);
        assertThat(association.getFinishObservedAt()).isEqualTo(changedAt);
        assertThat(association.getFinalizationCycleVersion()).isEqualTo(4);
        assertThat(association.getRecordingFinishedAt()).isEqualTo(closedAt);
        assertThat(association.getQuiescenceDueAt()).isEqualTo(changedAt.plusSeconds(60));
        assertThat(association.getMaxWaitAt()).isEqualTo(changedAt.plusSeconds(900));
    }

    private void observe(TranscriptSessionAssociation association, RecordingOutcome outcome,
            Instant closedAt, Instant observedAt) {
        if (outcome == RecordingOutcome.INCOMPLETE) {
            stateMachine.observeRecordingIncomplete(association, closedAt, observedAt, "CLOSURE_UNCONFIRMED");
        } else {
            stateMachine.observeRecordingFinished(association, closedAt, observedAt);
        }
    }

    private TranscriptSessionAssociation association(
            TranscriptFinalizationState state, long finalizedVersion, long cycleVersion) {
        TranscriptSessionAssociation association = new TranscriptSessionAssociation();
        association.setFinalizationState(state);
        association.setFinalizationVersion(finalizedVersion);
        association.setFinalizationCycleVersion(cycleVersion);
        return association;
    }
}
