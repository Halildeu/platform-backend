package com.example.endpointadmin.remoteaccess.bridge;

import com.example.endpointadmin.remoteaccess.DuressResponsePolicy.DuressSignal;
import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.MethodStrength;
import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.StepUpState;
import com.example.endpointadmin.remoteaccess.RemoteOperation;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.RemoteSessionPolicyEngine;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeBroker.BrokerOutcome;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import com.example.endpointadmin.remoteaccess.bridge.contract.CanonicalCommand;
import com.example.endpointadmin.remoteaccess.bridge.contract.ConsentLease;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.OperationRequest;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.SessionDeviceTrustVerifier.Basis;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 T-1b-ii — {@link RemoteBridgeBroker} control-plane dry-run: policy composition, fail-closed pipeline. */
class RemoteBridgeBrokerTest {

    private static final long T0 = 1_000_000_000_000L;
    private static final long NOW = T0 + 2000;
    private static final long TTL = 5 * 60_000L;
    private static final StepUpState UV = new StepUpState(T0 + 1000, T0, MethodStrength.WEBAUTHN_USER_VERIFICATION);
    private static final ConsentLease LEASE = new ConsentLease(true, false, T0 + 1_000_000L);

    private final KeyPair kp = ec();
    private final RemoteBridgePermitSigner signer = new RemoteBridgePermitSigner(kp.getPrivate(), "kid-1", "SHA256withECDSA");
    private final RemoteBridgePermitVerifier verifier = new RemoteBridgePermitVerifier(kp.getPublic(), "kid-1");
    private final java.util.List<String> recorded = new java.util.ArrayList<>();
    private final RemoteBridgeAuditSink sink = e -> recorded.add(e.eventType());
    private final RemoteBridgeBroker broker = new RemoteBridgeBroker(true, RemoteSessionPolicyEngine.PILOT, signer, sink, "policy-1", TTL);

    private static KeyPair ec() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(256);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RemoteBridgeTrustEvidence good() {
        return new RemoteBridgeTrustEvidence(true, true, true, UV, DuressSignal.NONE,
                Set.of(RemoteSessionCapability.VIEW_ONLY, RemoteSessionCapability.CONSTRAINED_PTY), LEASE, "dev-1", "operator@x");
    }

    private static OperationRequest pty(String cmd) {
        return new OperationRequest("sess-1", "op-1", RemoteOperation.PTY_COMMAND, cmd);
    }

    private static OperationRequest view() {
        return new OperationRequest("sess-1", "op-1", RemoteOperation.SCREEN_VIEW, null);
    }

    @Test
    void aFullyCompliantPtyOperationGetsASignedPermitBoundToTheCommand() {
        BrokerOutcome o = broker.handle(pty("hostname"), good(), State.ACTIVE, 1L, NOW);
        assertTrue(o.permitted());
        assertEquals(RemoteSessionCapability.CONSTRAINED_PTY, o.permit().capability());
        assertEquals(CanonicalCommand.of("hostname").hash(), o.permit().commandHash());
        assertTrue(verifier.verify(o.permit(), NOW)); // a real signature the agent can verify
        assertTrue(recorded.contains("ALLOW_DECISION:op-1")); // recorded before issuance (permit not yet issued)
    }

    @Test
    void aNonPilotOperationIsDeniedEvenIfACapabilityWouldPermitIt() {
        // KEYBOARD_INPUT is permitted by CONSTRAINED_PTY in the operation guard, but it is NOT a pilot transport
        // operation — the broker's explicit allowlist (SCREEN_VIEW + PTY_COMMAND only) denies it (Codex P1)
        OperationRequest keyboard = new OperationRequest("sess-1", "op-1", RemoteOperation.KEYBOARD_INPUT, null);
        BrokerOutcome o = broker.handle(keyboard, good(), State.ACTIVE, 1L, NOW);
        assertEquals(BrokerOutcome.Kind.DENY, o.kind());
        assertEquals("unsupported-operation", o.reason());
        for (RemoteOperation op : new RemoteOperation[]{RemoteOperation.MOUSE_INPUT, RemoteOperation.FILE_UPLOAD,
                RemoteOperation.CREDENTIAL_INJECT, RemoteOperation.PRIVILEGE_ELEVATE, RemoteOperation.OPEN_PORT_FORWARD}) {
            assertEquals("unsupported-operation",
                    broker.handle(new OperationRequest("sess-1", "op-1", op, null), good(), State.ACTIVE, 1L, NOW).reason(), op.name());
        }
    }

    @Test
    void aCompliantScreenViewGetsAPermitWithNoCommandHash() {
        BrokerOutcome o = broker.handle(view(), good(), State.ACTIVE, 1L, NOW);
        assertTrue(o.permitted());
        assertEquals(RemoteSessionCapability.VIEW_ONLY, o.permit().capability());
        assertEquals("", o.permit().commandHash());
        assertTrue(verifier.verify(o.permit(), NOW));
    }

    @Test
    void duressTerminatesTheSession() {
        RemoteBridgeTrustEvidence duress = new RemoteBridgeTrustEvidence(true, true, true, UV, DuressSignal.DURESS_CODE,
                Set.of(RemoteSessionCapability.CONSTRAINED_PTY), LEASE, "dev-1", "operator@x");
        assertEquals(BrokerOutcome.Kind.KILL, broker.handle(pty("hostname"), duress, State.ACTIVE, 1L, NOW).kind());
    }

    @Test
    void theEngineDryRunMatrixDeniesEachMissingPrecondition() {
        // no cert trust
        assertDeny(evidence(false, true, true, UV, Set.of(RemoteSessionCapability.CONSTRAINED_PTY)), pty("hostname"));
        // no attestation
        assertDeny(evidence(true, false, true, UV, Set.of(RemoteSessionCapability.CONSTRAINED_PTY)), pty("hostname"));
        // no device trust
        assertDeny(evidence(true, true, false, UV, Set.of(RemoteSessionCapability.CONSTRAINED_PTY)), pty("hostname"));
        // weak step-up
        assertDeny(evidence(true, true, true, new StepUpState(T0 + 1000, T0, MethodStrength.NONE),
                Set.of(RemoteSessionCapability.CONSTRAINED_PTY)), pty("hostname"));
        // capability not granted (no owner-token authorization for PTY)
        assertDeny(evidence(true, true, true, UV, Set.of(RemoteSessionCapability.VIEW_ONLY)), pty("hostname"));
        // empty granted capabilities
        assertDeny(evidence(true, true, true, UV, Set.of()), pty("hostname"));
    }

    @Test
    void aCryptoIdentityDenyCarriesOnlyBoundedPolicyDetailWhenProvided() {
        RemoteBridgeTrustEvidence evidence = new RemoteBridgeTrustEvidence(true, true, false,
                "no-active-enrolled-connected-peer", UV, DuressSignal.NONE,
                Set.of(RemoteSessionCapability.CONSTRAINED_PTY), LEASE, "dev-1", "operator@x");

        BrokerOutcome o = broker.handle(pty("hostname"), evidence, State.ACTIVE, 1L, NOW);

        assertEquals(BrokerOutcome.Kind.DENY, o.kind());
        assertEquals("policy:CRYPTO_IDENTITY", o.reason());
        assertEquals("no-active-enrolled-connected-peer", o.policyDetail());
    }

    @Test
    void defaultBrokerStillDeniesEnrollmentBackedDeviceTrustWithoutHardwareAttestation() {
        RemoteBridgeTrustEvidence evidence = new RemoteBridgeTrustEvidence(true, false, true,
                Basis.MACHINE_CERT_ENROLLMENT, "attestation-unverified", UV, DuressSignal.NONE,
                Set.of(RemoteSessionCapability.CONSTRAINED_PTY), LEASE, "dev-1", "operator@x");

        BrokerOutcome o = broker.handle(pty("hostname"), evidence, State.ACTIVE, 1L, NOW);

        assertEquals(BrokerOutcome.Kind.DENY, o.kind());
        assertEquals("policy:CRYPTO_IDENTITY", o.reason());
        assertEquals("attestation-unverified", o.policyDetail());
    }

    @Test
    void enrollmentBackedPilotBrokerPermitsConstrainedOperationWithMachineCertDeviceTrust() {
        RemoteBridgeBroker enrollmentBackedBroker = new RemoteBridgeBroker(true,
                RemoteSessionPolicyEngine.PILOT_ENROLLMENT_BACKED, signer, sink, "policy-1", TTL);
        RemoteBridgeTrustEvidence evidence = new RemoteBridgeTrustEvidence(true, false, true,
                Basis.MACHINE_CERT_ENROLLMENT, "attestation-unverified", UV, DuressSignal.NONE,
                Set.of(RemoteSessionCapability.CONSTRAINED_PTY), LEASE, "dev-1", "operator@x");

        BrokerOutcome o = enrollmentBackedBroker.handle(pty("hostname"), evidence, State.ACTIVE, 1L, NOW);

        assertTrue(o.permitted());
        assertEquals(RemoteSessionCapability.CONSTRAINED_PTY, o.permit().capability());
        assertTrue(verifier.verify(o.permit(), NOW));
    }

    @Test
    void enrollmentBackedPilotBrokerStillDeniesWhenBasisIsNotEnrollment() {
        RemoteBridgeBroker enrollmentBackedBroker = new RemoteBridgeBroker(true,
                RemoteSessionPolicyEngine.PILOT_ENROLLMENT_BACKED, signer, sink, "policy-1", TTL);
        RemoteBridgeTrustEvidence evidence = new RemoteBridgeTrustEvidence(true, false, true,
                Basis.NONE, "attestation-unverified", UV, DuressSignal.NONE,
                Set.of(RemoteSessionCapability.CONSTRAINED_PTY), LEASE, "dev-1", "operator@x");

        BrokerOutcome o = enrollmentBackedBroker.handle(pty("hostname"), evidence, State.ACTIVE, 1L, NOW);

        assertEquals(BrokerOutcome.Kind.DENY, o.kind());
        assertEquals("policy:CRYPTO_IDENTITY", o.reason());
    }

    @Test
    void theConsentLeaseMustBeActive() {
        // no lease
        assertEquals("no-active-consent-lease",
                broker.handle(pty("hostname"), withLease(ConsentLease.NONE), State.ACTIVE, 1L, NOW).reason());
        // expired lease
        assertEquals("no-active-consent-lease",
                broker.handle(pty("hostname"), withLease(new ConsentLease(true, false, T0)), State.ACTIVE, 1L, NOW).reason());
        // locally aborted
        assertEquals("no-active-consent-lease",
                broker.handle(pty("hostname"), withLease(new ConsentLease(true, true, T0 + 1_000_000L)), State.ACTIVE, 1L, NOW).reason());
    }

    @Test
    void theSessionMustBeActiveAndTheRequestWellFormed() {
        assertEquals("session-not-active", broker.handle(pty("hostname"), good(), State.CONSENT_GRANTED, 1L, NOW).reason());
        assertEquals("pty-without-command", broker.handle(pty(null), good(), State.ACTIVE, 1L, NOW).reason());
        assertEquals("non-pty-with-command",
                broker.handle(new OperationRequest("sess-1", "op-1", RemoteOperation.SCREEN_VIEW, "hostname"), good(), State.ACTIVE, 1L, NOW).reason());
        assertEquals("invalid-request",
                broker.handle(new OperationRequest("  ", "op-1", RemoteOperation.PTY_COMMAND, "hostname"), good(), State.ACTIVE, 1L, NOW).reason());
        assertEquals("malformed", broker.handle(null, good(), State.ACTIVE, 1L, NOW).reason());
        assertEquals("malformed", broker.handle(pty("hostname"), good(), State.ACTIVE, -1L, NOW).reason());
    }

    @Test
    void aDisabledBrokerDeniesEverything() {
        RemoteBridgeBroker off = new RemoteBridgeBroker(false, RemoteSessionPolicyEngine.PILOT, signer, sink, "policy-1", TTL);
        assertEquals("remote-bridge-disabled", off.handle(pty("hostname"), good(), State.ACTIVE, 1L, NOW).reason());
    }

    @Test
    void aRecordingFailureBlocksPermitIssuanceButNotAKill() {
        RemoteBridgeAuditSink throwing = e -> { throw new RuntimeException("durable write failed"); };
        RemoteBridgeBroker b = new RemoteBridgeBroker(true, RemoteSessionPolicyEngine.PILOT, signer, throwing, "policy-1", TTL);
        // ALLOW path: a recording failure -> no permit (fail-closed)
        assertEquals("recording-failed", b.handle(pty("hostname"), good(), State.ACTIVE, 1L, NOW).reason());
        // duress: the kill still fires even if recording throws (safety over audit)
        RemoteBridgeTrustEvidence duress = new RemoteBridgeTrustEvidence(true, true, true, UV, DuressSignal.PANIC_SIGNAL,
                Set.of(RemoteSessionCapability.CONSTRAINED_PTY), LEASE, "dev-1", "operator@x");
        assertEquals(BrokerOutcome.Kind.KILL, b.handle(pty("hostname"), duress, State.ACTIVE, 1L, NOW).kind());
    }

    @Test
    void constructorValidatesItsInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> new RemoteBridgeBroker(true, null, signer, sink, "p", TTL));
        assertThrows(IllegalArgumentException.class,
                () -> new RemoteBridgeBroker(true, RemoteSessionPolicyEngine.PILOT, signer, sink, "  ", TTL));
        assertThrows(IllegalArgumentException.class,
                () -> new RemoteBridgeBroker(true, RemoteSessionPolicyEngine.PILOT, signer, sink, "p", 0));
    }

    // --- helpers ---
    private void assertDeny(RemoteBridgeTrustEvidence evidence, OperationRequest request) {
        BrokerOutcome o = broker.handle(request, evidence, State.ACTIVE, 1L, NOW);
        assertFalse(o.permitted(), o.reason());
        assertEquals(BrokerOutcome.Kind.DENY, o.kind());
    }

    private static RemoteBridgeTrustEvidence evidence(boolean cert, boolean attest, boolean device, StepUpState step,
                                                      Set<RemoteSessionCapability> granted) {
        return new RemoteBridgeTrustEvidence(cert, attest, device, step, DuressSignal.NONE, granted, LEASE, "dev-1", "operator@x");
    }

    private static RemoteBridgeTrustEvidence withLease(ConsentLease lease) {
        return new RemoteBridgeTrustEvidence(true, true, true, UV, DuressSignal.NONE,
                Set.of(RemoteSessionCapability.CONSTRAINED_PTY), lease, "dev-1", "operator@x");
    }
}
