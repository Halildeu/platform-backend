package com.example.transcript.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.transcript.model.TranscriptFinalizationState;
import com.example.transcript.model.TranscriptSessionAssociation;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

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

    private TranscriptSessionAssociation association(
            TranscriptFinalizationState state, long finalizedVersion, long cycleVersion) {
        TranscriptSessionAssociation association = new TranscriptSessionAssociation();
        association.setFinalizationState(state);
        association.setFinalizationVersion(finalizedVersion);
        association.setFinalizationCycleVersion(cycleVersion);
        return association;
    }
}
