package com.example.endpointadmin.remoteaccess.preflight;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ViewOnlyCheckpointStateMachineTest {

    @Test
    void acceptsExactHappyPathAndRollbackClosure() {
        ViewOnlyCheckpointStateMachine.validateInitial(
                0, null, ViewOnlyCheckpointState.DECISION_AUTHORIZED, false);
        transition(ViewOnlyCheckpointState.DECISION_AUTHORIZED, ViewOnlyCheckpointState.LIVE_REVALIDATED, false);
        transition(ViewOnlyCheckpointState.LIVE_REVALIDATED, ViewOnlyCheckpointState.ACTIVATED, false);
        transition(ViewOnlyCheckpointState.ACTIVATED, ViewOnlyCheckpointState.CONSENT_PENDING, false);
        transition(ViewOnlyCheckpointState.CONSENT_PENDING, ViewOnlyCheckpointState.EVIDENCE_COLLECTED, false);
        transition(ViewOnlyCheckpointState.EVIDENCE_COLLECTED, ViewOnlyCheckpointState.EVIDENCE_VERIFIED, false);
        transition(ViewOnlyCheckpointState.EVIDENCE_VERIFIED, ViewOnlyCheckpointState.ARTIFACTS_STAGED, false);
        transition(ViewOnlyCheckpointState.ARTIFACTS_STAGED, ViewOnlyCheckpointState.ROLLBACK_PENDING, false);
        transition(ViewOnlyCheckpointState.ROLLBACK_PENDING, ViewOnlyCheckpointState.ROLLED_BACK, false);
        transition(ViewOnlyCheckpointState.ROLLED_BACK, ViewOnlyCheckpointState.COMPLETED, true);
    }

    @Test
    void rejectsSkippingConsentAndEvidence() {
        assertReason(
                ViewOnlyAuthorityError.STATE_TRANSITION_DENIED,
                () -> transition(ViewOnlyCheckpointState.ACTIVATED, ViewOnlyCheckpointState.ARTIFACTS_STAGED, false));
    }

    @Test
    void rejectsWrongInitialCheckpoint() {
        assertReason(
                ViewOnlyAuthorityError.STATE_TRANSITION_DENIED,
                () -> ViewOnlyCheckpointStateMachine.validateInitial(
                        1, null, ViewOnlyCheckpointState.DECISION_AUTHORIZED, false));
    }

    @Test
    void rejectsTerminalFlagOnNonTerminalState() {
        assertReason(
                ViewOnlyAuthorityError.TERMINAL_FLAG_INVALID,
                () -> transition(ViewOnlyCheckpointState.ROLLBACK_PENDING, ViewOnlyCheckpointState.ROLLED_BACK, true));
    }

    @Test
    void requiresTerminalFlagOnCompletedAndFailedClean() {
        assertReason(
                ViewOnlyAuthorityError.TERMINAL_FLAG_INVALID,
                () -> transition(ViewOnlyCheckpointState.ROLLED_BACK, ViewOnlyCheckpointState.COMPLETED, false));
        assertReason(
                ViewOnlyAuthorityError.TERMINAL_FLAG_INVALID,
                () -> transition(ViewOnlyCheckpointState.ROLLED_BACK, ViewOnlyCheckpointState.FAILED_CLEAN, false));
    }

    private static void transition(ViewOnlyCheckpointState from,
                                   ViewOnlyCheckpointState to,
                                   boolean terminal) {
        ViewOnlyCheckpointStateMachine.validateTransition(from, to, terminal);
    }

    private static void assertReason(ViewOnlyAuthorityError reason, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .extracting(error -> ((ViewOnlyAuthorityException) error).reason())
                .isEqualTo(reason);
    }
}
