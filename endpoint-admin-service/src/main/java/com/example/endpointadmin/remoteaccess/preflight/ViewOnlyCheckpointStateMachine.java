package com.example.endpointadmin.remoteaccess.preflight;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Exact transition table from the Faz 22.6 external-checkpoint authority contract. */
public final class ViewOnlyCheckpointStateMachine {
    private static final Map<ViewOnlyCheckpointState, Set<ViewOnlyCheckpointState>> TRANSITIONS = transitions();

    private ViewOnlyCheckpointStateMachine() {
    }

    public static void validateInitial(int sequence,
                                       ViewOnlyCheckpointState previousState,
                                       ViewOnlyCheckpointState state,
                                       boolean terminal) {
        if (sequence != 0 || previousState != null || state != ViewOnlyCheckpointState.DECISION_AUTHORIZED) {
            throw denied("sequence 0 must start at DECISION_AUTHORIZED with no previous state");
        }
        validateTerminalFlag(state, terminal);
    }

    public static void validateTransition(ViewOnlyCheckpointState previousState,
                                          ViewOnlyCheckpointState state,
                                          boolean terminal) {
        if (previousState == null || state == null
                || !TRANSITIONS.getOrDefault(previousState, Set.of()).contains(state)) {
            throw denied("checkpoint state transition is not authorized");
        }
        validateTerminalFlag(state, terminal);
    }

    private static void validateTerminalFlag(ViewOnlyCheckpointState state, boolean terminal) {
        if (state == null) {
            throw denied("checkpoint state is required");
        }
        if (state.mustBeTerminal() != terminal) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.TERMINAL_FLAG_INVALID,
                    "terminal flag does not match checkpoint state");
        }
    }

    private static ViewOnlyAuthorityException denied(String message) {
        return new ViewOnlyAuthorityException(ViewOnlyAuthorityError.STATE_TRANSITION_DENIED, message);
    }

    private static Map<ViewOnlyCheckpointState, Set<ViewOnlyCheckpointState>> transitions() {
        EnumMap<ViewOnlyCheckpointState, Set<ViewOnlyCheckpointState>> transitions =
                new EnumMap<>(ViewOnlyCheckpointState.class);
        transitions.put(ViewOnlyCheckpointState.DECISION_AUTHORIZED,
                EnumSet.of(ViewOnlyCheckpointState.LIVE_REVALIDATED,
                        ViewOnlyCheckpointState.FAILURE_CAPTURED,
                        ViewOnlyCheckpointState.ARTIFACTS_STAGE_FAILED));
        transitions.put(ViewOnlyCheckpointState.LIVE_REVALIDATED,
                EnumSet.of(ViewOnlyCheckpointState.ACTIVATED,
                        ViewOnlyCheckpointState.FAILURE_CAPTURED,
                        ViewOnlyCheckpointState.ARTIFACTS_STAGE_FAILED));
        transitions.put(ViewOnlyCheckpointState.ACTIVATED,
                EnumSet.of(ViewOnlyCheckpointState.CONSENT_PENDING,
                        ViewOnlyCheckpointState.FAILURE_CAPTURED,
                        ViewOnlyCheckpointState.ARTIFACTS_STAGE_FAILED));
        transitions.put(ViewOnlyCheckpointState.CONSENT_PENDING,
                EnumSet.of(ViewOnlyCheckpointState.EVIDENCE_COLLECTED,
                        ViewOnlyCheckpointState.FAILURE_CAPTURED,
                        ViewOnlyCheckpointState.ARTIFACTS_STAGE_FAILED));
        transitions.put(ViewOnlyCheckpointState.EVIDENCE_COLLECTED,
                EnumSet.of(ViewOnlyCheckpointState.EVIDENCE_VERIFIED,
                        ViewOnlyCheckpointState.FAILURE_CAPTURED,
                        ViewOnlyCheckpointState.ARTIFACTS_STAGE_FAILED));
        transitions.put(ViewOnlyCheckpointState.EVIDENCE_VERIFIED,
                EnumSet.of(ViewOnlyCheckpointState.ARTIFACTS_STAGED,
                        ViewOnlyCheckpointState.FAILURE_CAPTURED,
                        ViewOnlyCheckpointState.ARTIFACTS_STAGE_FAILED));
        transitions.put(ViewOnlyCheckpointState.FAILURE_CAPTURED,
                EnumSet.of(ViewOnlyCheckpointState.ARTIFACTS_STAGED,
                        ViewOnlyCheckpointState.ARTIFACTS_STAGE_FAILED));
        transitions.put(ViewOnlyCheckpointState.ARTIFACTS_STAGED,
                EnumSet.of(ViewOnlyCheckpointState.ROLLBACK_PENDING));
        transitions.put(ViewOnlyCheckpointState.ARTIFACTS_STAGE_FAILED,
                EnumSet.of(ViewOnlyCheckpointState.ROLLBACK_PENDING));
        transitions.put(ViewOnlyCheckpointState.ROLLBACK_PENDING,
                EnumSet.of(ViewOnlyCheckpointState.ROLLED_BACK));
        transitions.put(ViewOnlyCheckpointState.ROLLED_BACK,
                EnumSet.of(ViewOnlyCheckpointState.COMPLETED, ViewOnlyCheckpointState.FAILED_CLEAN));
        transitions.put(ViewOnlyCheckpointState.COMPLETED, EnumSet.noneOf(ViewOnlyCheckpointState.class));
        transitions.put(ViewOnlyCheckpointState.FAILED_CLEAN, EnumSet.noneOf(ViewOnlyCheckpointState.class));
        return Map.copyOf(transitions);
    }
}
