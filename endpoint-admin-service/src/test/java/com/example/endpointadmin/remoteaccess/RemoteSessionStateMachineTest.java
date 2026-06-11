package com.example.endpointadmin.remoteaccess;

import org.junit.jupiter.api.Test;

import static com.example.endpointadmin.remoteaccess.RemoteSessionState.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 broker state-machine unit tests (ADR-0033 §3). No runtime — pure policy.
 */
class RemoteSessionStateMachineTest {

    private final RemoteSessionStateMachine sm = new RemoteSessionStateMachine();

    private static RemoteSessionPreconditions allOk() {
        return new RemoteSessionPreconditions(true, true, true, true, true, true);
    }

    @Test
    void happyPathForwardTransitionsAreLegal() {
        assertTrue(sm.canTransition(REQUESTED, POLICY_EVALUATING));
        assertTrue(sm.canTransition(POLICY_EVALUATING, PENDING_TARGET_CONSENT));
        assertTrue(sm.canTransition(PENDING_TARGET_CONSENT, PENDING_DUAL_APPROVAL));
        assertTrue(sm.canTransition(PENDING_DUAL_APPROVAL, APPROVED));
        assertTrue(sm.canTransition(APPROVED, TOKEN_ISSUED));
        assertTrue(sm.canTransition(TOKEN_ISSUED, AGENT_CONNECTED));
        assertTrue(sm.canTransition(AGENT_CONNECTED, OPERATOR_CONNECTED));
        assertTrue(sm.canTransition(OPERATOR_CONNECTED, RECORDING_READY));
        assertTrue(sm.canTransition(ACTIVE, ENDING));
        assertTrue(sm.canTransition(ENDING, ENDED));
    }

    @Test
    void skippingStagesIsIllegal() {
        assertFalse(sm.canTransition(REQUESTED, APPROVED));
        assertFalse(sm.canTransition(APPROVED, ACTIVE));
        assertFalse(sm.canTransition(OPERATOR_CONNECTED, ACTIVE));
    }

    @Test
    void activeIsNeverEnteredViaCanTransition() {
        // Entering ACTIVE from any OTHER state must go through canActivate(); the ACTIVE→ACTIVE
        // self-transition is a legitimate idempotent no-op and is excluded here.
        for (RemoteSessionState from : RemoteSessionState.values()) {
            if (from == ACTIVE) {
                continue;
            }
            assertFalse(sm.canTransition(from, ACTIVE),
                    "ACTIVE must only be entered via canActivate(), not from " + from);
        }
    }

    @Test
    void canActivateRequiresRecordingReadyAndAllPreconditions() {
        assertTrue(sm.canActivate(RECORDING_READY, allOk()));
        // wrong source state
        assertFalse(sm.canActivate(OPERATOR_CONNECTED, allOk()));
        // missing a precondition → fail-closed
        assertFalse(sm.canActivate(RECORDING_READY,
                new RemoteSessionPreconditions(true, true, true, true, true, false)));
        assertFalse(sm.canActivate(RECORDING_READY, null));
    }

    @Test
    void transitionToActiveFailsClosedToFailedRecordingWhenRecorderMissing() {
        RemoteSessionPreconditions noRecorder =
                new RemoteSessionPreconditions(true, true, true, true, true, false);
        assertEquals(FAILED_RECORDING, sm.transition(RECORDING_READY, ACTIVE, noRecorder));
    }

    @Test
    void transitionToActiveSucceedsOnlyWhenAllSatisfied() {
        assertEquals(ACTIVE, sm.transition(RECORDING_READY, ACTIVE, allOk()));
    }

    @Test
    void abortBeatsConnectFromAnyNonTerminalState() {
        assertTrue(sm.canTransition(AGENT_CONNECTED, ABORTED));
        assertTrue(sm.canTransition(RECORDING_READY, REVOKED));
        assertTrue(sm.canTransition(POLICY_EVALUATING, DENIED));
        assertTrue(sm.canTransition(REQUESTED, EXPIRED));
    }

    @Test
    void terminalStatesAreIrreversibleSinks() {
        for (RemoteSessionState terminal : RemoteSessionState.values()) {
            if (!terminal.isTerminal()) {
                continue;
            }
            for (RemoteSessionState to : RemoteSessionState.values()) {
                if (to == terminal) {
                    continue;
                }
                assertFalse(sm.canTransition(terminal, to),
                        terminal + " is terminal but allowed → " + to);
            }
        }
    }

    @Test
    void selfTransitionIsIdempotentNoOp() {
        assertTrue(sm.canTransition(ACTIVE, ACTIVE));
        assertTrue(sm.canTransition(REQUESTED, REQUESTED));
    }

    @Test
    void illegalTransitionThrows() {
        assertThrows(RemoteSessionStateMachine.IllegalStateTransitionException.class,
                () -> sm.transition(REQUESTED, APPROVED, allOk()));
    }

    // ---- evaluateActivation: total, non-throwing, explicit outcome (Codex 019eb522 absorb) ----

    @Test
    void evaluateActivationGrantedOnlyWhenAllSatisfiedAtRecordingReady() {
        assertEquals(RemoteSessionStateMachine.ActivationOutcome.GRANTED,
                sm.evaluateActivation(RECORDING_READY, allOk()));
    }

    @Test
    void evaluateActivationBlockedPendingForRecoverablePreconditions() {
        // consent missing → recoverable PENDING, NOT a FAILED terminal, NOT ACTIVE
        RemoteSessionPreconditions noConsent =
                new RemoteSessionPreconditions(true, false, true, true, true, true);
        assertEquals(RemoteSessionStateMachine.ActivationOutcome.BLOCKED_PENDING,
                sm.evaluateActivation(RECORDING_READY, noConsent));
        // dual-approval missing → also pending
        RemoteSessionPreconditions noApproval =
                new RemoteSessionPreconditions(true, true, false, true, true, true);
        assertEquals(RemoteSessionStateMachine.ActivationOutcome.BLOCKED_PENDING,
                sm.evaluateActivation(RECORDING_READY, noApproval));
        // wrong stage → pending (not yet activatable)
        assertEquals(RemoteSessionStateMachine.ActivationOutcome.BLOCKED_PENDING,
                sm.evaluateActivation(OPERATOR_CONNECTED, allOk()));
    }

    @Test
    void evaluateActivationHardFailuresMapToTerminals() {
        assertEquals(RemoteSessionStateMachine.ActivationOutcome.FAILED_POLICY,
                sm.evaluateActivation(RECORDING_READY,
                        new RemoteSessionPreconditions(false, true, true, true, true, true)));
        assertEquals(RemoteSessionStateMachine.ActivationOutcome.FAILED_AGENT_ATTESTATION,
                sm.evaluateActivation(RECORDING_READY,
                        new RemoteSessionPreconditions(true, true, true, true, false, true)));
        assertEquals(RemoteSessionStateMachine.ActivationOutcome.FAILED_RECORDING,
                sm.evaluateActivation(RECORDING_READY,
                        new RemoteSessionPreconditions(true, true, true, true, true, false)));
        // null preconditions → fail-closed policy failure, never NPE
        assertEquals(RemoteSessionStateMachine.ActivationOutcome.FAILED_POLICY,
                sm.evaluateActivation(RECORDING_READY, null));
    }

    @Test
    void transitionToActiveThrowsExplicitlyWhenBlockedPending() {
        RemoteSessionPreconditions noToken =
                new RemoteSessionPreconditions(true, true, true, false, true, true);
        // fail-closed: never returns ACTIVE; the blocked case is an explicit throw, not a silent state
        assertThrows(RemoteSessionStateMachine.IllegalStateTransitionException.class,
                () -> sm.transition(RECORDING_READY, ACTIVE, noToken));
    }

    @Test
    void transitionActiveToActiveIsIdempotentNoOp() {
        // consistency with canTransition(ACTIVE, ACTIVE)==true: self-transition is a no-op,
        // not a precondition re-check / throw (Codex consistency absorb).
        assertEquals(ACTIVE, sm.transition(ACTIVE, ACTIVE, null));
    }

    @Test
    void transitionFromTerminalIsAlwaysIllegalEvenToActive() {
        // monotonicity: no transition may leave a terminal state, including the ACTIVE arm with null pre.
        assertThrows(RemoteSessionStateMachine.IllegalStateTransitionException.class,
                () -> sm.transition(ENDED, ACTIVE, null));
        assertThrows(RemoteSessionStateMachine.IllegalStateTransitionException.class,
                () -> sm.transition(FAILED_RECORDING, ENDING, allOk()));
    }
}
