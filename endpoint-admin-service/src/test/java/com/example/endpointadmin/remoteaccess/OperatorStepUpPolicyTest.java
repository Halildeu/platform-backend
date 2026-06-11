package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.Decision;
import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.MethodStrength;
import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.Requirement;
import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.StepUpState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 D-6 — {@link OperatorStepUpPolicy} step-up freshness gate: per-op strength/TTL, anti-replay, fail-closed. */
class OperatorStepUpPolicyTest {

    private final OperatorStepUpPolicy pilot = OperatorStepUpPolicy.PILOT_DEFAULT_POLICY;
    private static final long T0 = 1_000_000_000_000L; // session start
    private static final long MIN = 60_000L;

    private static StepUpState state(long lastStepUp, MethodStrength m) {
        return new StepUpState(lastStepUp, T0, m);
    }

    @Test
    void aFreshStrongThisSessionStepUpSatisfiesTheOperation() {
        // PTY needs UV within 5 min
        assertEquals(Decision.SATISFIED,
                pilot.decide(RemoteOperation.PTY_COMMAND, state(T0 + 1000, MethodStrength.WEBAUTHN_USER_VERIFICATION), T0 + 2000));
        // SCREEN_VIEW needs only UP within 30 min
        assertEquals(Decision.SATISFIED,
                pilot.decide(RemoteOperation.SCREEN_VIEW, state(T0 + 1000, MethodStrength.WEBAUTHN_USER_PRESENCE), T0 + 1000 + 29 * MIN));
    }

    @Test
    void aMethodWeakerThanTheOperationRequiresForcesStepUp() {
        // UP is too weak for PTY (needs UV)
        assertEquals(Decision.REQUIRE_STEP_UP,
                pilot.decide(RemoteOperation.PTY_COMMAND, state(T0 + 1000, MethodStrength.WEBAUTHN_USER_PRESENCE), T0 + 2000));
        // NONE never satisfies anything
        assertEquals(Decision.REQUIRE_STEP_UP,
                pilot.decide(RemoteOperation.SCREEN_VIEW, state(T0 + 1000, MethodStrength.NONE), T0 + 2000));
    }

    @Test
    void aStaleStepUpBeyondTheFreshnessWindowForcesStepUp() {
        // PTY UV 5 min + 1 ms ago -> stale
        assertEquals(Decision.REQUIRE_STEP_UP,
                pilot.decide(RemoteOperation.PTY_COMMAND, state(T0 + 1000, MethodStrength.WEBAUTHN_USER_VERIFICATION), T0 + 1000 + 5 * MIN + 1));
        // SCREEN_VIEW UP 30 min + 1 ms ago -> stale
        assertEquals(Decision.REQUIRE_STEP_UP,
                pilot.decide(RemoteOperation.SCREEN_VIEW, state(T0 + 1000, MethodStrength.WEBAUTHN_USER_PRESENCE), T0 + 1000 + 30 * MIN + 1));
    }

    @Test
    void exactlyAtTheTtlBoundaryIsStillFresh() {
        assertEquals(Decision.SATISFIED,
                pilot.decide(RemoteOperation.PTY_COMMAND, state(T0 + 1000, MethodStrength.WEBAUTHN_USER_VERIFICATION), T0 + 1000 + 5 * MIN));
    }

    @Test
    void aStepUpFromBeforeThisSessionCannotSatisfyIt() {
        // strong + fresh-by-TTL, but it predates sessionStart -> replay/session-mixing guard fires
        StepUpState preSession = new StepUpState(T0 - 1000, T0, MethodStrength.WEBAUTHN_USER_VERIFICATION);
        assertEquals(Decision.REQUIRE_STEP_UP, pilot.decide(RemoteOperation.PTY_COMMAND, preSession, T0 + 1000));
    }

    @Test
    void clockSkewIsFailClosed() {
        // now before session start
        assertEquals(Decision.REQUIRE_STEP_UP,
                pilot.decide(RemoteOperation.SCREEN_VIEW, state(T0 + 1000, MethodStrength.WEBAUTHN_USER_PRESENCE), T0 - 1));
        // now before the recorded step-up
        StepUpState future = new StepUpState(T0 + 5000, T0, MethodStrength.WEBAUTHN_USER_VERIFICATION);
        assertEquals(Decision.REQUIRE_STEP_UP, pilot.decide(RemoteOperation.PTY_COMMAND, future, T0 + 1000));
    }

    @Test
    void corruptedNegativeTimestampsAreFailClosed() {
        // a negative (pre-epoch) timestamp anywhere is nonsensical -> REQUIRE_STEP_UP, never SATISFIED
        assertEquals(Decision.REQUIRE_STEP_UP,
                pilot.decide(RemoteOperation.PTY_COMMAND, new StepUpState(-1, T0, MethodStrength.WEBAUTHN_USER_VERIFICATION), T0 + 2000));
        assertEquals(Decision.REQUIRE_STEP_UP,
                pilot.decide(RemoteOperation.PTY_COMMAND, new StepUpState(T0 + 1000, -1, MethodStrength.WEBAUTHN_USER_VERIFICATION), T0 + 2000));
        assertEquals(Decision.REQUIRE_STEP_UP,
                pilot.decide(RemoteOperation.PTY_COMMAND, state(T0 + 1000, MethodStrength.WEBAUTHN_USER_VERIFICATION), -1));
    }

    @Test
    void anOffForPilotOperationFacesTheStrongestShortestGate() {
        // CREDENTIAL_INJECT is not in the pilot map -> offForPilotDefault (UV, 1 min)
        assertEquals(Decision.SATISFIED,
                pilot.decide(RemoteOperation.CREDENTIAL_INJECT, state(T0 + 1000, MethodStrength.WEBAUTHN_USER_VERIFICATION), T0 + 2000));
        // UV but 2 min old -> beyond the 1-min fallback window
        assertEquals(Decision.REQUIRE_STEP_UP,
                pilot.decide(RemoteOperation.CREDENTIAL_INJECT, state(T0 + 1000, MethodStrength.WEBAUTHN_USER_VERIFICATION), T0 + 1000 + 2 * MIN));
        // UP is too weak for the fallback (needs UV)
        assertEquals(Decision.REQUIRE_STEP_UP,
                pilot.decide(RemoteOperation.FILE_UPLOAD, state(T0 + 1000, MethodStrength.WEBAUTHN_USER_PRESENCE), T0 + 2000));
    }

    @Test
    void nullOperationOrStateIsMalformedNotMerelyRequireStepUp() {
        assertEquals(Decision.DENIED_MALFORMED,
                pilot.decide(null, state(T0 + 1000, MethodStrength.WEBAUTHN_USER_VERIFICATION), T0 + 2000));
        assertEquals(Decision.DENIED_MALFORMED, pilot.decide(RemoteOperation.SCREEN_VIEW, null, T0 + 2000));
    }

    @Test
    void methodStrengthIsTotallyOrderedByRankNotOrdinal() {
        assertTrue(MethodStrength.WEBAUTHN_USER_VERIFICATION.meetsRequired(MethodStrength.WEBAUTHN_USER_PRESENCE));
        assertTrue(MethodStrength.WEBAUTHN_USER_PRESENCE.meetsRequired(MethodStrength.WEBAUTHN_USER_PRESENCE));
        assertFalse(MethodStrength.WEBAUTHN_USER_PRESENCE.meetsRequired(MethodStrength.WEBAUTHN_USER_VERIFICATION));
        assertFalse(MethodStrength.NONE.meetsRequired(MethodStrength.OTP));
        assertTrue(MethodStrength.NONE.meetsRequired(MethodStrength.NONE));
        assertTrue(MethodStrength.WEBAUTHN_USER_VERIFICATION.rank() > MethodStrength.OTP.rank());
    }

    @Test
    void onlySatisfiedDecisionReportsSatisfied() {
        for (Decision d : Decision.values()) {
            assertEquals(d == Decision.SATISFIED, d.satisfied(), d.name());
        }
    }

    @Test
    void recordsValidateTheirInputs() {
        assertThrows(IllegalArgumentException.class, () -> new Requirement(null, 1000));
        assertThrows(IllegalArgumentException.class, () -> new Requirement(MethodStrength.OTP, -1));
        // a null method strength in a state degrades to NONE (fail-closed), not a crash
        assertEquals(MethodStrength.NONE, new StepUpState(T0, T0, null).methodStrength());
    }
}
