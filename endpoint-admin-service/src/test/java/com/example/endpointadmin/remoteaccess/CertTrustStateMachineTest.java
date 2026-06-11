package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.RemoteSessionStateMachine.ActivationOutcome;
import com.example.endpointadmin.remoteaccess.RemoteSessionStateMachine.KillReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Faz 22.6 B1.2 — the {@code certValid} (cert-trust) precondition in the state machine: it is a HARD
 * failure (FAILED_CERT_TRUST, never recoverable-pending), and its firstLost precedence is
 * {@code TOKEN > certValid > certBound} (Codex 019eb694 Q2): a token-store partition still surfaces as
 * TOKEN (load-bearing invariant), and cert trust is reported before cert binding.
 */
class CertTrustStateMachineTest {

    private final RemoteSessionStateMachine sm = new RemoteSessionStateMachine();

    /** All other preconditions satisfied; only token / certValid / certBound vary. */
    private static RemoteSessionPreconditions pre(boolean tokenBound, boolean certValid, boolean certBound) {
        return new RemoteSessionPreconditions(true, true, true, tokenBound, certValid, certBound, true, true);
    }

    @Test
    void certTrustLossIsAHardActivationFailure() {
        RemoteSessionPreconditions untrusted = pre(true, false, true); // cert untrusted, all else OK
        assertFalse(sm.canActivate(RemoteSessionState.RECORDING_READY, untrusted));
        assertEquals(ActivationOutcome.FAILED_CERT_TRUST,
                sm.evaluateActivation(RemoteSessionState.RECORDING_READY, untrusted));
        assertEquals(RemoteSessionState.FAILED_AGENT_ATTESTATION,
                sm.transition(RemoteSessionState.RECORDING_READY, RemoteSessionState.ACTIVE, untrusted));
    }

    @Test
    void midSessionCertTrustLossKills() {
        RemoteSessionStateMachine.Reevaluation r =
                sm.reevaluateActive(RemoteSessionState.ACTIVE, pre(true, false, true));
        assertEquals(RemoteSessionState.FAILED_AGENT_ATTESTATION, r.target());
        assertEquals(KillReason.CERT_TRUST_LOST, r.reason());
    }

    @Test
    void tokenLossWinsOverCertTrustAndBinding() {
        // total loss (partition-style): token + certValid + certBound all false → TOKEN is reported, so the
        // token-store-partition invariant is never masked by a trust/binding loss.
        assertEquals(KillReason.TOKEN_REVOKED,
                sm.reevaluateActive(RemoteSessionState.ACTIVE, pre(false, false, false)).reason());
    }

    @Test
    void certTrustWinsOverCertBinding() {
        // token OK, both cert guarantees lost → CERT_TRUST before CERT_BINDING (trust precedes binding).
        assertEquals(KillReason.CERT_TRUST_LOST,
                sm.reevaluateActive(RemoteSessionState.ACTIVE, pre(true, false, false)).reason());
    }

    @Test
    void certBindingStillReportedWhenTrustHolds() {
        // token OK, trust OK, binding lost → CERT_BINDING (the B1.1c precedence is preserved beneath trust).
        assertEquals(KillReason.CERT_BINDING_LOST,
                sm.reevaluateActive(RemoteSessionState.ACTIVE, pre(true, true, false)).reason());
    }
}
