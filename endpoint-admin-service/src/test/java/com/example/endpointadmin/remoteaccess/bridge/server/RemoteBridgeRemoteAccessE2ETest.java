package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.AttestationVerifier;
import com.example.endpointadmin.remoteaccess.CertTrustEvaluator;
import com.example.endpointadmin.remoteaccess.DeviceIdentityVerifier;
import com.example.endpointadmin.remoteaccess.DuressResponsePolicy.DuressSignal;
import com.example.endpointadmin.remoteaccess.RemoteOperation;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.RemoteSessionPolicyEngine;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeAuditSink;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeBroker;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeBroker.BrokerOutcome.Kind;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgePermitSigner;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.Event;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.ConsentResult;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.OperationRequest;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.SessionRequest;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.BrokerControlPlane;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.OwnerTokenGate;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerEvidenceParser;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerTrustLedger;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService.OperatorOutcome;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService.SessionOpenOutcome;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSession;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.TrustEvidenceAssembler;
import com.example.endpointadmin.remoteaccess.bridge.proto.Envelope;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 T-4a-ii slice-4b-4 (Codex 019ebd7f) — the FULL operator↔agent↔broker↔transport orchestration
 * lifecycle, end to end: an operator opens an attended session, the agent reports consent, the operator
 * activates, then issues an operation that runs through the broker and routes to the transport.
 *
 * <p>This is the PERMIT-AGNOSTIC lifecycle slice (Codex A): with the current truth — no real device PKI
 * (B1.4d) and no operator FIDO2 step-up (D) wired — the honest broker outcome is DENY. A real PERMIT e2e is a
 * later gate AFTER those trust roots land; mocking them here to force a PERMIT would manufacture trust the
 * system does not actually have (No Fake Work). What this proves is that EVERY orchestration hop is wired and
 * fail-closed: session open → consent prompt → consent absorb → activate → operation → broker → transport.
 */
class RemoteBridgeRemoteAccessE2ETest {

    private static final long NOW = 3_000_000L;
    private static final String PEER = "peer-1";
    // a canonical operator-tenant UUID — the store enforces the canonical form (slice-4c-2b-2b)
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    private static RemoteBridgeBroker broker() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            RemoteBridgePermitSigner signer = new RemoteBridgePermitSigner(
                    g.generateKeyPair().getPrivate(), "kid-1", RemoteBridgePermitSigner.PERMIT_ALG);
            return new RemoteBridgeBroker(true, RemoteSessionPolicyEngine.PILOT, signer, event -> { },
                    "rb-v1", 60_000L);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static PeerTrustLedger ledger() {
        return new PeerTrustLedger(
                (cert, now) -> CertTrustEvaluator.TrustDecision.NOT_TRUSTED,
                (evidence, now) -> AttestationVerifier.AttestationDecision.MISSING,
                new DeviceIdentityVerifier(Set.of(), DeviceIdentityVerifier.DeviceProtectionLevel.SECURE_ELEMENT_OR_TPM),
                PeerEvidenceParser.FAIL_CLOSED, 30_000L);
    }

    private static final class CapturingObserver implements StreamObserver<Envelope> {
        final List<Envelope> sent = new ArrayList<>();
        boolean completed;

        @Override public void onNext(Envelope value) {
            sent.add(value);
        }

        @Override public void onError(Throwable t) { }

        @Override public void onCompleted() {
            completed = true;
        }
    }

    @Test
    void theFullAttendedSessionLifecycleIsWiredEndToEnd() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        PeerTrustLedger ledger = ledger();
        ControlStreamRegistry registry = new ControlStreamRegistry();
        RemoteBridgeAuditSink audit = event -> { };
        BrokerControlPlane controlPlane = new BrokerControlPlane(ledger, store, audit, () -> NOW);
        TrustEvidenceAssembler assembler = new TrustEvidenceAssembler(ledger, OwnerTokenGate.DENY_ALL,
                (sid, now) -> DuressSignal.NONE); // clean duress so the chain reaches the policy engine
        RemoteBridgeOperatorService operator = new RemoteBridgeOperatorService(store, assembler, broker(),
                registry, () -> NOW, 120_000L);

        CapturingObserver agent = new CapturingObserver();
        PeerIdentity peer = new PeerIdentity(PEER, Optional.of("dev-1"), List.of());
        registry.register(peer, new ControlStreamHandle(agent));

        // 1) operator opens the attended session → the agent receives a consent prompt on CONTROL
        SessionOpenOutcome open = operator.openSession(
                new SessionRequest("s1", "dev-1", "operator@x", "remote support",
                        Set.of(RemoteSessionCapability.VIEW_ONLY)),
                peer, TENANT, "Operator X");
        assertTrue(open.opened());
        assertTrue(open.consentPromptSent());
        assertTrue(agent.sent.stream().anyMatch(Envelope::hasConsentPrompt), "agent received the consent prompt");
        RemoteBridgeSession session = store.bySessionId("s1").orElseThrow();
        assertEquals(State.CONSENT_PENDING, session.state());

        // 2) the agent reports the end-user's consent → the control plane absorbs it → CONSENT_GRANTED + lease
        controlPlane.onConsentResult(peer, new ConsentResult("s1", true, "1", NOW, NOW + 300_000L));
        assertEquals(State.CONSENT_GRANTED, session.state());

        // 3) the operator activates the granted session → ACTIVE
        assertTrue(session.transition(Event.ACTIVATE).accepted());
        assertEquals(State.ACTIVE, session.state());

        // 4) the operator issues an operation → broker → transport routing. With no device PKI / step-up wired,
        //    the honest verdict is DENY (no granted capability under DENY_ALL); nothing is pushed to the agent.
        int beforeOp = agent.sent.size();
        OperatorOutcome op = operator.handleOperationRequest(
                new OperationRequest("s1", "op-1", RemoteOperation.SCREEN_VIEW, null));
        assertTrue(op.accepted(), "the request reached the broker (not a pre-broker reject)");
        assertEquals(Kind.DENY, op.brokerOutcome().kind(), "honest outcome with no real trust roots is DENY");
        assertFalse(op.transportPushed());
        assertEquals(beforeOp, agent.sent.size(), "a denied operation pushes nothing further to the agent");
        assertFalse(agent.completed, "a denied operation does not close the session stream");
    }

    @Test
    void aDeniedConsentEndsTheSessionBeforeAnyOperation() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        PeerTrustLedger ledger = ledger();
        ControlStreamRegistry registry = new ControlStreamRegistry();
        BrokerControlPlane controlPlane = new BrokerControlPlane(ledger, store, event -> { }, () -> NOW);
        TrustEvidenceAssembler assembler = new TrustEvidenceAssembler(ledger, OwnerTokenGate.DENY_ALL,
                (sid, now) -> DuressSignal.NONE);
        RemoteBridgeOperatorService operator = new RemoteBridgeOperatorService(store, assembler, broker(),
                registry, () -> NOW, 120_000L);

        CapturingObserver agent = new CapturingObserver();
        PeerIdentity peer = new PeerIdentity(PEER, Optional.of("dev-1"), List.of());
        registry.register(peer, new ControlStreamHandle(agent));

        operator.openSession(new SessionRequest("s1", "dev-1", "operator@x", "support",
                Set.of(RemoteSessionCapability.VIEW_ONLY)), peer, TENANT, "Operator X");

        // the end-user REFUSES → the control plane denies consent; there is NO path from here to ACTIVE
        controlPlane.onConsentResult(peer, new ConsentResult("s1", false, "1", NOW, NOW + 300_000L));
        assertTrue(store.bySessionId("s1").isEmpty()
                        || store.bySessionId("s1").orElseThrow().state() != State.ACTIVE,
                "a refused consent never reaches ACTIVE");

        // any subsequent operation is never permitted (session denied/terminal/evicted) — nothing transports
        OperatorOutcome op = operator.handleOperationRequest(
                new OperationRequest("s1", "op-1", RemoteOperation.SCREEN_VIEW, null));
        assertFalse(op.transportPushed(), "no operation is permitted after a refused consent");
    }
}
