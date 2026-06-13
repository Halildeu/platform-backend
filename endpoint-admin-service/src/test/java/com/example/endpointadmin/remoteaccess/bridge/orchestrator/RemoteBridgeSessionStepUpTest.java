package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.MethodStrength;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpVerification;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.Verdict;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Faz 22.6 D step-up wiring (Codex 019ebe06) — the session records a VERIFIED operator step-up with a
 * VERIFIED-only advance + monotonic guard: a non-VERIFIED/null verification, a pre-session timestamp, or a
 * backward timestamp is a fail-closed no-op; the default is the weakest (no step-up).
 */
class RemoteBridgeSessionStepUpTest {

    private static final long SESSION_START = 10_000L;
    // a canonical operator-tenant UUID — mirrors the store-enforced canonical form (slice-4c-2b-2b)
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    private static RemoteBridgeSession session() {
        return new RemoteBridgeSession("s1", "peer-1", "dev-1", "operator@x", TENANT, "Operator X",
                Set.of(RemoteSessionCapability.VIEW_ONLY), SESSION_START + 60_000L, SESSION_START, State.ACTIVE);
    }

    private static StepUpVerification verified(MethodStrength strength, long ts) {
        return new StepUpVerification(Verdict.VERIFIED, strength, ts);
    }

    @Test
    void aFreshSessionHasNoStepUp() {
        RemoteBridgeSession s = session();
        assertEquals(0L, s.lastStepUpEpochMillis());
        assertEquals(MethodStrength.NONE, s.stepUpStrength());
    }

    @Test
    void aVerifiedStepUpAdvancesTheFreshnessAndStrength() {
        RemoteBridgeSession s = session();
        s.recordStepUp(verified(MethodStrength.WEBAUTHN_USER_VERIFICATION, SESSION_START + 500L));
        assertEquals(SESSION_START + 500L, s.lastStepUpEpochMillis());
        assertEquals(MethodStrength.WEBAUTHN_USER_VERIFICATION, s.stepUpStrength());
    }

    @Test
    void aNonVerifiedOrNullVerificationIsANoOp() {
        RemoteBridgeSession s = session();
        s.recordStepUp(verified(MethodStrength.WEBAUTHN_USER_VERIFICATION, SESSION_START + 500L));
        // a later FAILED attempt must NOT erase the earlier valid step-up
        s.recordStepUp(new StepUpVerification(Verdict.SIGNATURE_INVALID, MethodStrength.NONE, 0L));
        s.recordStepUp(null);
        assertEquals(SESSION_START + 500L, s.lastStepUpEpochMillis());
        assertEquals(MethodStrength.WEBAUTHN_USER_VERIFICATION, s.stepUpStrength());
    }

    @Test
    void aPreSessionTimestampIsRefused() {
        RemoteBridgeSession s = session();
        s.recordStepUp(verified(MethodStrength.WEBAUTHN_USER_VERIFICATION, SESSION_START - 1L));
        assertEquals(0L, s.lastStepUpEpochMillis());
        assertEquals(MethodStrength.NONE, s.stepUpStrength());
    }

    @Test
    void aBackwardTimestampDoesNotRewindTheFreshness() {
        RemoteBridgeSession s = session();
        s.recordStepUp(verified(MethodStrength.WEBAUTHN_USER_VERIFICATION, SESSION_START + 2_000L));
        // an older (backward) verification must not move the freshness back
        s.recordStepUp(verified(MethodStrength.WEBAUTHN_USER_PRESENCE, SESSION_START + 1_000L));
        assertEquals(SESSION_START + 2_000L, s.lastStepUpEpochMillis());
        assertEquals(MethodStrength.WEBAUTHN_USER_VERIFICATION, s.stepUpStrength());
    }
}
