package com.example.transcript.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.transcript.model.TranscriptFinalizationState;
import com.example.transcript.model.TranscriptSessionAssociation;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TranscriptFinalizationStateMachineTest {

    private final TranscriptFinalizationStateMachine stateMachine =
            new TranscriptFinalizationStateMachine(new TranscriptFinalizationProperties());

    @Test
    void recordingFinishedStartsBoundedCycleAfterObservedLateResultWindow() {
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
                .isEqualTo(Instant.parse("2026-07-17T10:06:10.987654Z"));
        assertThat(association.getMaxWaitAt())
                .isEqualTo(Instant.parse("2026-07-17T10:15:10.987654Z"));
        assertThat(association.getQuiescenceDueAt()).isEqualTo(association.getMinWaitAt());
    }

    @Test
    void distinctLateContentMovesDueButNeverBeyondCycleDeadline() {
        TranscriptSessionAssociation association = association(TranscriptFinalizationState.AWAITING_FINISH, 0, 0);
        Instant observedAt = Instant.parse("2026-07-17T10:00:00Z");
        stateMachine.observeRecordingFinished(association, observedAt, observedAt);

        stateMachine.recordDistinctContent(association, observedAt.plusSeconds(14 * 60 + 45));

        assertThat(association.getLastContentChangedAt()).isEqualTo(observedAt.plusSeconds(14 * 60 + 45));
        assertThat(association.getQuiescenceDueAt()).isEqualTo(observedAt.plusSeconds(15 * 60));
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
