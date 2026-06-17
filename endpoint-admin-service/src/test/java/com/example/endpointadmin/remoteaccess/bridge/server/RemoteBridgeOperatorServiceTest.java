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
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.OperationRequest;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.SessionRequest;
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

import java.security.KeyPair;
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
 * Faz 22.6 T-4a-ii slice-4b-2 (Codex 019ebd7f) — the operator service routes the broker verdict to the
 * transport: a duress (AMBIGUOUS until wired) KILLs + drives the session terminal + evicts (Codex S1); a
 * deny pushes nothing but frees the peer slot; an unknown/malformed request is rejected before the broker is
 * consulted. The PERMIT happy path (a full policy pass) is the e2e slice (4b-4). In the server package for
 * ControlStreamHandle access.
 */
class RemoteBridgeOperatorServiceTest {

    private static final long NOW = 2_000_000L;
    // a canonical operator-tenant UUID — the store enforces the canonical form (slice-4c-2b-2b)
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    private static KeyPair ecKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A broker with a no-op audit sink — KILL/DENY use best-effort audit (not the durable record gate). */
    private static RemoteBridgeBroker broker() {
        RemoteBridgePermitSigner signer = new RemoteBridgePermitSigner(
                ecKeyPair().getPrivate(), "kid-1", RemoteBridgePermitSigner.PERMIT_ALG);
        RemoteBridgeAuditSink sink = event -> { };
        return new RemoteBridgeBroker(true, RemoteSessionPolicyEngine.PILOT, signer, sink, "rb-v1", 60_000L);
    }

    private static PeerTrustLedger emptyLedger() {
        return new PeerTrustLedger(
                (cert, now) -> CertTrustEvaluator.TrustDecision.NOT_TRUSTED,
                (evidence, now) -> AttestationVerifier.AttestationDecision.MISSING,
                new DeviceIdentityVerifier(Set.of(), DeviceIdentityVerifier.DeviceProtectionLevel.SECURE_ELEMENT_OR_TPM),
                PeerEvidenceParser.FAIL_CLOSED, 30_000L);
    }

    private static TrustEvidenceAssembler assembler(TrustEvidenceAssembler.DuressSignalSource duress) {
        return new TrustEvidenceAssembler(emptyLedger(), OwnerTokenGate.DENY_ALL, duress);
    }

    /** A capturing CONTROL StreamObserver so a transport kill/permit is observable. */
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

    /** Open a session and drive its state machine to ACTIVE with an active consent lease. */
    private static RemoteBridgeSession activeSession(RemoteBridgeSessionStore store, String sessionId,
                                                     String peerKey, Set<RemoteSessionCapability> caps) {
        PeerIdentity peer = new PeerIdentity(peerKey, Optional.of("dev-1"), List.of());
        store.open(new SessionRequest(sessionId, "dev-1", "operator@x", null, caps), peer, TENANT, "Operator X",
                NOW + 60_000L, NOW);
        RemoteBridgeSession s = store.bySessionId(sessionId).orElseThrow();
        s.transition(Event.ENABLE);
        s.transition(Event.REQUEST_SESSION);
        s.transition(Event.PROMPT_CONSENT);
        s.transition(Event.CONSENT_GRANTED);
        s.grantConsent(true, NOW + 300_000L);
        s.transition(Event.ACTIVATE);
        return s;
    }

    @Test
    void aMalformedRequestIsRejectedBeforeTheBroker() {
        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(
                new RemoteBridgeSessionStore(), assembler((sid, now) -> DuressSignal.NONE), broker(),
                new ControlStreamRegistry(), () -> NOW, 120_000L);

        OperatorOutcome outcome = service.handleOperationRequest(
                new OperationRequest(" ", "op-1", RemoteOperation.SCREEN_VIEW, null));

        assertFalse(outcome.accepted());
        assertEquals("malformed-request", outcome.rejectReason());
    }

    @Test
    void anUnknownSessionIsRejectedBeforeTheBroker() {
        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(
                new RemoteBridgeSessionStore(), assembler((sid, now) -> DuressSignal.NONE), broker(),
                new ControlStreamRegistry(), () -> NOW, 120_000L);

        OperatorOutcome outcome = service.handleOperationRequest(
                new OperationRequest("ghost", "op-1", RemoteOperation.SCREEN_VIEW, null));

        assertFalse(outcome.accepted());
        assertEquals("unknown-session", outcome.rejectReason());
    }

    @Test
    void anAmbiguousDuressKillsTheSessionAndEvictsIt() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        registry.register(new PeerIdentity("peer-1", Optional.of("dev-1"), List.of()),
                new ControlStreamHandle(observer));
        activeSession(store, "s1", "peer-1", Set.of(RemoteSessionCapability.VIEW_ONLY));

        // the UNWIRED duress source (AMBIGUOUS) → the broker kills regardless of capability
        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(store,
                assembler(TrustEvidenceAssembler.DuressSignalSource.AMBIGUOUS_UNTIL_WIRED), broker(), registry,
                () -> NOW, 120_000L);

        OperatorOutcome outcome = service.handleOperationRequest(
                new OperationRequest("s1", "op-1", RemoteOperation.SCREEN_VIEW, null));

        assertEquals(Kind.KILL, outcome.brokerOutcome().kind());
        assertFalse(outcome.transportPushed());
        assertTrue(observer.sent.stream().anyMatch(Envelope::hasKill), "a KILL must be pushed on CONTROL");
        assertTrue(observer.completed, "the transport stream must be closed by the kill");
        assertTrue(store.bySessionId("s1").isEmpty(), "the killed session must be evicted (no ACTIVE ghost)");
    }

    @Test
    void aCleanDuressWithNoGrantedCapabilityIsDeniedAndFreesThePeerSlot() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        PeerIdentity peer = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());
        registry.register(peer, new ControlStreamHandle(observer));
        RemoteBridgeSession deniedSession = activeSession(store, "s1", "peer-1",
                Set.of(RemoteSessionCapability.VIEW_ONLY));

        // a clean duress source → the broker reaches the policy engine; DENY_ALL gate → no capability → DENY
        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(store,
                assembler((sid, now) -> DuressSignal.NONE), broker(), registry, () -> NOW, 120_000L);

        OperatorOutcome outcome = service.handleOperationRequest(
                new OperationRequest("s1", "op-1", RemoteOperation.SCREEN_VIEW, null));

        assertEquals(Kind.DENY, outcome.brokerOutcome().kind());
        assertFalse(outcome.transportPushed());
        assertTrue(observer.sent.isEmpty(), "a deny pushes nothing to the agent");
        assertTrue(deniedSession.state().isTerminal(), "a deny terminalizes the operator session locally");
        assertTrue(store.bySessionId("s1").isEmpty(), "a deny evicts the session so no ACTIVE ghost remains");

        SessionOpenOutcome reopened = service.openSession(
                new SessionRequest("s2", "dev-1", "operator@x", "retry support", Set.of(RemoteSessionCapability.VIEW_ONLY)),
                peer, TENANT, "Operator X");

        assertTrue(reopened.opened(), "a denied policy decision must not block a follow-up attended session");
        assertEquals("s2", reopened.sessionId());
        assertTrue(observer.sent.stream().anyMatch(Envelope::hasConsentPrompt),
                "the reopened session must push a fresh consent prompt");
    }

    // --- slice-4b-3 consent flow (openSession) ------------------------------

    @Test
    void openSessionPushesAConsentPromptToAConnectedPeer() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        PeerIdentity peer = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());
        registry.register(peer, new ControlStreamHandle(observer));
        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(store,
                assembler((sid, now) -> DuressSignal.NONE), broker(), registry, () -> NOW, 120_000L);

        SessionOpenOutcome outcome = service.openSession(
                new SessionRequest("s1", "dev-1", "operator@x", "remote support", Set.of(RemoteSessionCapability.VIEW_ONLY)),
                peer, TENANT, "Operator X");

        assertTrue(outcome.opened());
        assertTrue(outcome.consentPromptSent());
        assertEquals("s1", outcome.sessionId());
        assertTrue(observer.sent.stream().anyMatch(Envelope::hasConsentPrompt),
                "a consent prompt must be pushed on CONTROL");
        assertTrue(store.bySessionId("s1").isPresent());
        assertEquals(State.CONSENT_PENDING, store.bySessionId("s1").orElseThrow().state(),
                "the store walks the new session to CONSENT_PENDING");
    }

    @Test
    void openSessionRefusesAPeerWithNoLiveStreamBeforeCreatingTheSession() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(store,
                assembler((sid, now) -> DuressSignal.NONE), broker(), new ControlStreamRegistry(), () -> NOW,
                120_000L);

        SessionOpenOutcome outcome = service.openSession(
                new SessionRequest("s1", "dev-1", "operator@x", "support", Set.of(RemoteSessionCapability.VIEW_ONLY)),
                new PeerIdentity("peer-1", Optional.of("dev-1"), List.of()), TENANT, "Operator X");

        assertFalse(outcome.opened());
        assertEquals("peer-not-connected", outcome.rejectReason());
        assertTrue(store.bySessionId("s1").isEmpty(), "no session is created on the pre-guard reject");
    }

    @Test
    void openSessionRejectsAMalformedRequest() {
        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(new RemoteBridgeSessionStore(),
                assembler((sid, now) -> DuressSignal.NONE), broker(), new ControlStreamRegistry(), () -> NOW,
                120_000L);

        SessionOpenOutcome outcome = service.openSession(null, null, TENANT, "Operator X");

        assertFalse(outcome.opened());
        assertEquals("malformed-request", outcome.rejectReason());
    }

    // --- slice-4c-2b-2b operator-tenant fail-closed guard (Codex 019ebe06) ----

    @Test
    void openSessionWithABlankTenantIsRefusedAndCreatesNoSessionOrPrompt() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        PeerIdentity peer = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());
        registry.register(peer, new ControlStreamHandle(observer));
        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(store,
                assembler((sid, now) -> DuressSignal.NONE), broker(), registry, () -> NOW, 120_000L);

        // a CONNECTED peer (the pre-guard passes) but a BLANK tenant — the store refuses past the pre-guard
        SessionOpenOutcome outcome = service.openSession(
                new SessionRequest("s1", "dev-1", "operator@x", "support", Set.of(RemoteSessionCapability.VIEW_ONLY)),
                peer, "  ", "Operator X");

        assertFalse(outcome.opened());
        assertEquals("invalid-operator-tenant", outcome.rejectReason());
        assertTrue(store.bySessionId("s1").isEmpty(), "no session is created for a blank tenant");
        assertTrue(observer.sent.isEmpty(), "no consent prompt is pushed for a refused open");
    }

    @Test
    void openSessionWithANonCanonicalTenantIsRefused() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity peer = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());
        registry.register(peer, new ControlStreamHandle(new CapturingObserver()));
        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(store,
                assembler((sid, now) -> DuressSignal.NONE), broker(), registry, () -> NOW, 120_000L);

        // "t-1" is a non-UUID tenant on a connected peer — a future caller passing a non-canonical tenant must
        // not silently weaken the controller's tenant-scoped ownership guard
        SessionOpenOutcome outcome = service.openSession(
                new SessionRequest("s1", "dev-1", "operator@x", "support", Set.of(RemoteSessionCapability.VIEW_ONLY)),
                peer, "t-1", "Operator X");

        assertFalse(outcome.opened());
        assertEquals("invalid-operator-tenant", outcome.rejectReason());
        assertTrue(store.bySessionId("s1").isEmpty(), "no session is created for a non-canonical tenant");
    }

    @Test
    void storeOpenRefusesEveryNonCanonicalTenantButAcceptsTheCanonicalOne() {
        PeerIdentity peer = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());
        Set<RemoteSessionCapability> caps = Set.of(RemoteSessionCapability.VIEW_ONLY);
        String[] nonCanonical = {
                null, "", "  ", "t-1", "not-a-uuid",
                "11111111-1111-1111-1111-11111111111g",   // non-hex digit
                "11111111111111111111111111111111",        // no dashes
                "11111111-1111-1111-1111-111111111111 ",   // trailing whitespace (store does NOT trim)
                "11111111-1111-1111-1111-11111111111A",     // uppercase = non-canonical (toString is lowercase)
        };

        for (String badTenant : nonCanonical) {
            RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
            RemoteBridgeSessionStore.OpenResult result = store.open(
                    new SessionRequest("s1", "dev-1", "operator@x", null, caps),
                    peer, badTenant, "Operator X", NOW + 60_000L, NOW);
            assertTrue(result instanceof RemoteBridgeSessionStore.Refused,
                    "tenant <" + badTenant + "> must be refused");
            assertEquals("invalid-operator-tenant",
                    ((RemoteBridgeSessionStore.Refused) result).reason(), "tenant <" + badTenant + "> reason");
            assertEquals(0, store.liveCount(), "no session created for tenant <" + badTenant + ">");
        }

        // the canonical lowercase form the controller emits via UUID.toString() still opens — no behavior change
        RemoteBridgeSessionStore canonicalStore = new RemoteBridgeSessionStore();
        RemoteBridgeSessionStore.OpenResult opened = canonicalStore.open(
                new SessionRequest("s1", "dev-1", "operator@x", null, caps), peer, TENANT, "Operator X",
                NOW + 60_000L, NOW);
        assertTrue(opened instanceof RemoteBridgeSessionStore.Opened, "the canonical tenant opens");
        assertEquals(1, canonicalStore.liveCount());
    }
}
