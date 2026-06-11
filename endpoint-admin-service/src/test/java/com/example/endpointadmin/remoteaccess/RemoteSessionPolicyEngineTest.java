package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.DuressResponsePolicy.DuressSignal;
import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.MethodStrength;
import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.StepUpState;
import com.example.endpointadmin.remoteaccess.RemoteSessionPolicyEngine.Gate;
import com.example.endpointadmin.remoteaccess.RemoteSessionPolicyEngine.Outcome;
import com.example.endpointadmin.remoteaccess.RemoteSessionPolicyEngine.SessionContext;
import com.example.endpointadmin.remoteaccess.RemoteSessionPolicyEngine.SessionDecision;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 — {@link RemoteSessionPolicyEngine} composition: priority-ordered, fail-closed, duress-overrides. */
class RemoteSessionPolicyEngineTest {

    private final RemoteSessionPolicyEngine engine = RemoteSessionPolicyEngine.PILOT;

    private static final long T0 = 1_000_000_000_000L;
    private static final long NOW = T0 + 2000;
    private static final StepUpState UV = new StepUpState(T0 + 1000, T0, MethodStrength.WEBAUTHN_USER_VERIFICATION);

    /** A fully-good SCREEN_VIEW context (every gate passes). */
    private static SessionContext goodView() {
        return new SessionContext(DuressSignal.NONE, true, true, true, UV,
                Set.of(RemoteSessionCapability.VIEW_ONLY), RemoteOperation.SCREEN_VIEW, null, NOW);
    }

    @Test
    void aFullyCompliantViewSessionIsAllowed() {
        SessionDecision d = engine.evaluate(goodView());
        assertTrue(d.allowed());
        assertEquals(Outcome.ALLOW, d.outcome());
        assertEquals(Gate.NONE, d.gate());
    }

    @Test
    void aCompliantConstrainedPtyCommandIsAllowed() {
        SessionDecision d = engine.evaluate(new SessionContext(DuressSignal.NONE, true, true, true, UV,
                Set.of(RemoteSessionCapability.CONSTRAINED_PTY), RemoteOperation.PTY_COMMAND, "hostname", NOW));
        assertEquals(Outcome.ALLOW, d.outcome());
    }

    @Test
    void duressOverridesEverythingIncludingAnOtherwiseMalformedContext() {
        // duress + everything else bad (untrusted crypto, null op/granted/state) -> still TERMINATE_DURESS
        SessionDecision d = engine.evaluate(new SessionContext(DuressSignal.DURESS_CODE, false, false, false,
                null, null, null, null, NOW));
        assertEquals(Outcome.TERMINATE_DURESS, d.outcome());
        assertEquals(Gate.DURESS, d.gate());
        // a null duress signal fail-safes to duress too (D-7)
        assertEquals(Outcome.TERMINATE_DURESS,
                engine.evaluate(new SessionContext(null, true, true, true, UV,
                        Set.of(RemoteSessionCapability.VIEW_ONLY), RemoteOperation.SCREEN_VIEW, null, NOW)).outcome());
    }

    @Test
    void anUntrustedCryptoIdentityIsDeniedBeforeStepUp() {
        // cert untrusted AND a weak step-up -> the crypto gate wins (priority), reported as CRYPTO_IDENTITY
        SessionContext c = new SessionContext(DuressSignal.NONE, false, true, true,
                new StepUpState(T0 + 1000, T0, MethodStrength.NONE),
                Set.of(RemoteSessionCapability.VIEW_ONLY), RemoteOperation.SCREEN_VIEW, null, NOW);
        SessionDecision d = engine.evaluate(c);
        assertEquals(Outcome.DENY, d.outcome());
        assertEquals(Gate.CRYPTO_IDENTITY, d.gate());
        // each crypto precondition denies
        assertEquals(Gate.CRYPTO_IDENTITY, engine.evaluate(new SessionContext(DuressSignal.NONE, true, true, false, UV,
                Set.of(RemoteSessionCapability.VIEW_ONLY), RemoteOperation.SCREEN_VIEW, null, NOW)).gate()); // device
        assertEquals(Gate.CRYPTO_IDENTITY, engine.evaluate(new SessionContext(DuressSignal.NONE, true, false, true, UV,
                Set.of(RemoteSessionCapability.VIEW_ONLY), RemoteOperation.SCREEN_VIEW, null, NOW)).gate()); // attestation
    }

    @Test
    void aStaleOrWeakStepUpIsDenied() {
        SessionDecision d = engine.evaluate(new SessionContext(DuressSignal.NONE, true, true, true,
                new StepUpState(T0 + 1000, T0, MethodStrength.NONE),
                Set.of(RemoteSessionCapability.VIEW_ONLY), RemoteOperation.SCREEN_VIEW, null, NOW));
        assertEquals(Outcome.DENY, d.outcome());
        assertEquals(Gate.STEP_UP, d.gate());
    }

    @Test
    void anOperationNotPermittedByTheGrantedCapabilitiesIsDenied() {
        // VIEW_ONLY granted, but PTY_COMMAND attempted -> operation gate denies
        SessionDecision d = engine.evaluate(new SessionContext(DuressSignal.NONE, true, true, true, UV,
                Set.of(RemoteSessionCapability.VIEW_ONLY), RemoteOperation.PTY_COMMAND, "hostname", NOW));
        assertEquals(Outcome.DENY, d.outcome());
        assertEquals(Gate.OPERATION, d.gate());
    }

    @Test
    void aDisallowedOrMalformedPtyCommandIsDeniedAtTheCommandGate() {
        // CONSTRAINED_PTY + PTY_COMMAND, but the command is not allowlisted
        assertEquals(Gate.COMMAND, engine.evaluate(new SessionContext(DuressSignal.NONE, true, true, true, UV,
                Set.of(RemoteSessionCapability.CONSTRAINED_PTY), RemoteOperation.PTY_COMMAND, "rm -rf /", NOW)).gate());
        // a null / blank command line for a PTY command is not permitted (null-safety, Codex)
        assertEquals(Gate.COMMAND, engine.evaluate(new SessionContext(DuressSignal.NONE, true, true, true, UV,
                Set.of(RemoteSessionCapability.CONSTRAINED_PTY), RemoteOperation.PTY_COMMAND, null, NOW)).gate());
        assertEquals(Gate.COMMAND, engine.evaluate(new SessionContext(DuressSignal.NONE, true, true, true, UV,
                Set.of(RemoteSessionCapability.CONSTRAINED_PTY), RemoteOperation.PTY_COMMAND, "   ", NOW)).gate());
    }

    @Test
    void nullContextOrFieldsAreMalformed() {
        assertEquals(Gate.MALFORMED, engine.evaluate(null).gate());
        // null operation (duress NONE so we pass duress)
        assertEquals(Gate.MALFORMED, engine.evaluate(new SessionContext(DuressSignal.NONE, true, true, true, UV,
                Set.of(RemoteSessionCapability.VIEW_ONLY), null, null, NOW)).gate());
        // null granted
        assertEquals(Gate.MALFORMED, engine.evaluate(new SessionContext(DuressSignal.NONE, true, true, true, UV,
                null, RemoteOperation.SCREEN_VIEW, null, NOW)).gate());
        // null step-up state
        assertEquals(Gate.MALFORMED, engine.evaluate(new SessionContext(DuressSignal.NONE, true, true, true, null,
                Set.of(RemoteSessionCapability.VIEW_ONLY), RemoteOperation.SCREEN_VIEW, null, NOW)).gate());
        // negative timestamp (Codex)
        assertEquals(Gate.MALFORMED, engine.evaluate(new SessionContext(DuressSignal.NONE, true, true, true, UV,
                Set.of(RemoteSessionCapability.VIEW_ONLY), RemoteOperation.SCREEN_VIEW, null, -1)).gate());
    }

    @Test
    void duressBeatsTheMalformedNullOperationCheck() {
        // a duress signal terminates even though the operation is null (duress is checked before null-op)
        SessionDecision d = engine.evaluate(new SessionContext(DuressSignal.PANIC_SIGNAL, true, true, true, UV,
                Set.of(RemoteSessionCapability.VIEW_ONLY), null, null, NOW));
        assertEquals(Outcome.TERMINATE_DURESS, d.outcome());
    }

    @Test
    void onlyAllowOutcomeReportsAllowed() {
        assertTrue(engine.evaluate(goodView()).allowed());
        for (Outcome o : Outcome.values()) {
            // construct a decision per outcome via the records to check allowed() invariant
            assertEquals(o == Outcome.ALLOW, new SessionDecision(o, Gate.NONE, "x").allowed(), o.name());
        }
    }

    @Test
    void constructionRequiresAllThreeGates() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RemoteSessionPolicyEngine(null, OperatorStepUpPolicy.PILOT_DEFAULT_POLICY, ConstrainedPtyGate.PILOT));
    }
}
