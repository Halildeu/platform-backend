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
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.OperationRequest;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.SessionRequest;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.AuditEvent;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.BrokerControlPlane;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.DeviceKeyChallengeStore;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeAgentErrorLedger;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.TpmDeviceKeySessionEvidenceStore;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.OwnerTokenGate;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerEvidenceParser;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerTrustLedger;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService.OperatorOutcome;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService.SessionDuressSignalOutcome;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService.SessionCloseOutcome;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService.SessionOpenOutcome;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSession;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.SessionDuressSignalStore;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.TrustEvidenceAssembler;
import com.example.endpointadmin.remoteaccess.bridge.proto.Envelope;
import com.example.endpointadmin.remoteaccess.bridge.wire.RemoteBridgeProtoAdapter;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.HexFormat;
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

    private static String operatorSubjectAuditHash(String operatorSubject) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(operatorSubject.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
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

    private static RemoteBridgeAuditSink operatorAuditSink() {
        return event -> { };
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

    private static final class RecordingAuditSink implements RemoteBridgeAuditSink {
        final List<String> eventTypes = new ArrayList<>();

        @Override
        public void record(AuditEvent event) {
            eventTypes.add(event.eventType());
        }

        boolean hasExact(String eventType) {
            return eventTypes.contains(eventType);
        }
    }

    private static final class LatchingNonBlankAuditSink implements RemoteBridgeAuditSink {
        final List<AuditEvent> events = new ArrayList<>();
        boolean broken;

        @Override
        public void record(AuditEvent event) {
            if (broken) {
                throw new IllegalStateException("recording sink is latched broken");
            }
            if (event == null || event.contentHash() == null || event.contentHash().isBlank()) {
                broken = true;
                throw new IllegalStateException("blank durable audit content hash");
            }
            events.add(event);
        }

        boolean hasEventPrefix(String prefix) {
            return events.stream().anyMatch(event -> event.eventType().startsWith(prefix));
        }

        boolean allContentHashesLookSha256() {
            return events.stream().allMatch(event -> event.contentHash().matches("[0-9a-f]{64}"));
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

    private static StreamObserver<Envelope> throwingObserver() {
        return new StreamObserver<>() {
            @Override public void onNext(Envelope value) {
                throw new RuntimeException("transport down");
            }

            @Override public void onError(Throwable t) { }

            @Override public void onCompleted() { }
        };
    }

    // --- device-key session attestation issuance (Faz 22.6 #548 step-5b) ------

    @Test
    void enabledOpenSessionIssuesADeviceKeyChallengeBeforeTheConsentPrompt() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        PeerIdentity peer = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());
        registry.register(peer, new ControlStreamHandle(observer));
        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(store,
                assembler((sid, now) -> DuressSignal.NONE), broker(), registry, operatorAuditSink(), () -> NOW,
                120_000L, new DeviceKeyChallengeStore(), new TpmDeviceKeySessionEvidenceStore(), true, 120_000L);

        SessionOpenOutcome outcome = service.openSession(
                new SessionRequest("s1", "dev-1", "operator@x", "remote support",
                        Set.of(RemoteSessionCapability.VIEW_ONLY)), peer, TENANT, "Operator X");

        assertTrue(outcome.opened());
        assertTrue(observer.sent.get(0).hasDeviceKeyChallenge(),
                "the device-key challenge is pushed FIRST, before the consent prompt");
        assertEquals("s1", observer.sent.get(0).getSessionId(), "the challenge envelope carries the session id");
        assertTrue(observer.sent.get(1).hasConsentPrompt(), "the consent prompt follows the challenge");
    }

    @Test
    void enabledOpenSessionChallengeCanBeConsumedByTheControlPlaneForTheSameLiveSession() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        RecordingAuditSink audit = new RecordingAuditSink();
        PeerIdentity peer = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());
        registry.register(peer, new ControlStreamHandle(observer));
        DeviceKeyChallengeStore challengeStore = new DeviceKeyChallengeStore();
        TpmDeviceKeySessionEvidenceStore evidenceStore = new TpmDeviceKeySessionEvidenceStore();
        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(store,
                assembler((sid, now) -> DuressSignal.NONE), broker(), registry, audit, () -> NOW,
                120_000L, challengeStore, evidenceStore, true, 120_000L);
        BrokerControlPlane controlPlane = new BrokerControlPlane(emptyLedger(), store, audit, () -> NOW + 1_000L,
                new RemoteBridgeAgentErrorLedger(0), challengeStore, evidenceStore);

        SessionOpenOutcome outcome = service.openSession(
                new SessionRequest("s1", "dev-1", "operator@x", "remote support",
                        Set.of(RemoteSessionCapability.VIEW_ONLY)), peer, TENANT, "Operator X");
        RemoteBridgeMessages.DeviceKeyChallenge challenge =
                RemoteBridgeProtoAdapter.decode(observer.sent.get(0).getDeviceKeyChallenge()).orElseThrow();

        controlPlane.onDeviceKeyAttestationResponse(peer, shapedDeviceKeyResponse(challenge.challengeId()));

        String challengeHash = shortAuditHash(challenge.challengeId());
        assertTrue(outcome.opened());
        assertTrue(evidenceStore.consumeFresh("s1", peer.transportPeerKey(), NOW + 1_000L).isPresent(),
                "the challenge issued during openSession must be consumable by the same control plane store");
        assertTrue(audit.hasExact("DEVICE_KEY_CHALLENGE_ISSUED:challenge_hash=" + challengeHash));
        assertTrue(audit.hasExact("DEVICE_KEY_CHALLENGE_SENT:challenge_hash=" + challengeHash));
        assertTrue(audit.hasExact("DEVICE_KEY_EVIDENCE_STORED:challenge_hash=" + challengeHash));
    }

    @Test
    void deviceKeyChallengeDiagnosticsDoNotPoisonCleanDuressRecording() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        PeerIdentity peer = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());
        registry.register(peer, new ControlStreamHandle(observer));
        SessionDuressSignalStore duressStore = new SessionDuressSignalStore(120_000L);
        LatchingNonBlankAuditSink audit = new LatchingNonBlankAuditSink();
        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(store,
                new TrustEvidenceAssembler(emptyLedger(), OwnerTokenGate.DENY_ALL, duressStore),
                broker(), registry, audit, () -> NOW, 120_000L, duressStore,
                new DeviceKeyChallengeStore(), new TpmDeviceKeySessionEvidenceStore(), true, 120_000L);

        SessionOpenOutcome open = service.openSession(
                new SessionRequest("s1", "dev-1", "operator@x", "remote support",
                        Set.of(RemoteSessionCapability.VIEW_ONLY)), peer, TENANT, "Operator X");
        RemoteBridgeSession session = store.bySessionId("s1").orElseThrow();
        session.transition(Event.CONSENT_GRANTED);
        session.grantConsent(true, NOW + 300_000L);
        session.transition(Event.ACTIVATE);

        SessionDuressSignalOutcome signal =
                service.recordDuressSignal("s1", "operator@x", DuressSignal.NONE);

        assertTrue(open.opened());
        assertTrue(signal.accepted(),
                "device-key diagnostic audit must not latch the durable recorder before duress NONE is recorded");
        assertFalse(signal.terminal());
        assertTrue(audit.hasEventPrefix("DEVICE_KEY_CHALLENGE_ISSUED:"));
        assertTrue(audit.hasEventPrefix("DEVICE_KEY_CHALLENGE_SENT:"));
        assertTrue(audit.hasEventPrefix("OPERATOR_DURESS_SIGNAL:NONE"));
        assertTrue(audit.allContentHashesLookSha256());
        assertFalse(audit.broken);
    }

    @Test
    void aDeviceKeyChallengeThatCannotBeDeliveredKillsAndEvictsTheSession() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity peer = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());
        // a registered-but-dead stream: isConnected passes the pre-guard, but the challenge send fails →
        // kill + evict + reject, exactly like an undelivered consent prompt (no orphan CONSENT_PENDING session)
        registry.register(peer, new ControlStreamHandle(throwingObserver()));
        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(store,
                assembler((sid, now) -> DuressSignal.NONE), broker(), registry, operatorAuditSink(), () -> NOW,
                120_000L, new DeviceKeyChallengeStore(), new TpmDeviceKeySessionEvidenceStore(), true, 120_000L);

        SessionOpenOutcome outcome = service.openSession(
                new SessionRequest("s1", "dev-1", "operator@x", "remote support",
                        Set.of(RemoteSessionCapability.VIEW_ONLY)), peer, TENANT, "Operator X");

        assertFalse(outcome.opened());
        assertEquals("device-key-challenge-not-delivered", outcome.rejectReason());
        assertTrue(store.bySessionId("s1").isEmpty(), "the just-opened session is killed + evicted, not orphaned");
    }

    @Test
    void disabledOpenSessionIssuesNoDeviceKeyChallenge() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        PeerIdentity peer = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());
        registry.register(peer, new ControlStreamHandle(observer));
        // the 7-arg ctor = device-key issuance DISABLED (the non-REAL default): no challenge is emitted
        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(store,
                assembler((sid, now) -> DuressSignal.NONE), broker(), registry, operatorAuditSink(), () -> NOW,
                120_000L);

        SessionOpenOutcome outcome = service.openSession(
                new SessionRequest("s1", "dev-1", "operator@x", "remote support",
                        Set.of(RemoteSessionCapability.VIEW_ONLY)), peer, TENANT, "Operator X");

        assertTrue(outcome.opened());
        assertTrue(observer.sent.stream().noneMatch(Envelope::hasDeviceKeyChallenge),
                "a non-REAL deployment emits NO device-key challenge");
        assertTrue(observer.sent.stream().anyMatch(Envelope::hasConsentPrompt),
                "the consent prompt still goes out");
    }

    @Test
    void aReusedSessionIdDoesNotInheritThePriorSessionsPendingChallenge() {
        // Codex REVISE F1: the broker sessionId is client-supplied + reusable after the prior session is evicted.
        // A NEW session reusing the id must clear the prior pending challenge so a late response for it can never
        // be redeemed into the new same-id session — openSession evicts stale (sessionId, peer) state before issue.
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity peer = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());
        registry.register(peer, new ControlStreamHandle(new CapturingObserver()));
        DeviceKeyChallengeStore challengeStore = new DeviceKeyChallengeStore();
        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(store,
                assembler((sid, now) -> DuressSignal.NONE), broker(), registry, operatorAuditSink(), () -> NOW,
                120_000L, challengeStore, new TpmDeviceKeySessionEvidenceStore(), true, 120_000L);

        // a prior "s1" left a still-valid pending challenge for (s1, peer-1) behind
        var stale = challengeStore.issue("s1", "peer-1", 600_000L, NOW);

        SessionOpenOutcome reopened = service.openSession(
                new SessionRequest("s1", "dev-1", "operator@x", "retry", Set.of(RemoteSessionCapability.VIEW_ONLY)),
                peer, TENANT, "Operator X");

        assertTrue(reopened.opened());
        assertTrue(challengeStore.consume(stale.challengeId(), "peer-1", "s1", NOW).isEmpty(),
                "a late response for the prior session's evicted challenge cannot be redeemed into the reused id");
    }

    /** A well-SHAPED (mappable) device-key response — crypto remains verifier-owned. */
    private static RemoteBridgeMessages.DeviceKeyAttestationResponse shapedDeviceKeyResponse(String challengeId) {
        String b = "AQ=="; // base64 of one byte → satisfies required-non-empty fields
        return new RemoteBridgeMessages.DeviceKeyAttestationResponse(challengeId,
                "faz22.6.device-key-session.v1", b, b, b, "", "", List.of(), b, b, b, b, b, b, NOW);
    }

    private static String shortAuditHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aMalformedRequestIsRejectedBeforeTheBroker() {
        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(
                new RemoteBridgeSessionStore(), assembler((sid, now) -> DuressSignal.NONE), broker(),
                new ControlStreamRegistry(), operatorAuditSink(), () -> NOW, 120_000L);

        OperatorOutcome outcome = service.handleOperationRequest(
                new OperationRequest(" ", "op-1", RemoteOperation.SCREEN_VIEW, null));

        assertFalse(outcome.accepted());
        assertEquals("malformed-request", outcome.rejectReason());
    }

    @Test
    void anUnknownSessionIsRejectedBeforeTheBroker() {
        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(
                new RemoteBridgeSessionStore(), assembler((sid, now) -> DuressSignal.NONE), broker(),
                new ControlStreamRegistry(), operatorAuditSink(), () -> NOW, 120_000L);

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
        LatchingNonBlankAuditSink deviceTrustAudits = new LatchingNonBlankAuditSink();

        // the UNWIRED duress source (AMBIGUOUS) → the broker kills regardless of capability
        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(store,
                assembler(TrustEvidenceAssembler.DuressSignalSource.AMBIGUOUS_UNTIL_WIRED), broker(), registry,
                deviceTrustAudits,
                () -> NOW, 120_000L);

        OperatorOutcome outcome = service.handleOperationRequest(
                new OperationRequest("s1", "op-1", RemoteOperation.SCREEN_VIEW, null));

        Optional<AuditEvent> deviceDecisionAudit = deviceTrustAudits.events.stream()
                .filter(event -> event.eventType().startsWith("DEVICE_TRUST_DECISION:"))
                .findFirst();
        assertTrue(deviceDecisionAudit.isPresent(),
                "the device-trust verifier decision must be auditable even when duress later kills");
        assertEquals("s1", deviceDecisionAudit.orElseThrow().sessionId());
        assertTrue(deviceDecisionAudit.orElseThrow().eventType().contains("trusted=false"));
        assertTrue(deviceDecisionAudit.orElseThrow().eventType().contains("basis=NONE"));
        assertTrue(deviceDecisionAudit.orElseThrow().eventType().contains("effective_trusted=false"));
        assertTrue(deviceDecisionAudit.orElseThrow().eventType().contains("reason=device-trust-not-configured"));
        assertTrue(deviceTrustAudits.allContentHashesLookSha256());
        assertFalse(deviceTrustAudits.broken);
        assertEquals(Kind.KILL, outcome.brokerOutcome().kind());
        assertFalse(outcome.transportPushed());
        assertTrue(observer.sent.stream().anyMatch(Envelope::hasKill), "a KILL must be pushed on CONTROL");
        assertTrue(observer.completed, "the transport stream must be closed by the kill");
        assertTrue(store.bySessionId("s1").isEmpty(), "the killed session must be evicted (no ACTIVE ghost)");
    }

    @Test
    void aSessionBackedCleanDuressSignalLetsTheBrokerReachNormalPolicy() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        PeerIdentity peer = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());
        registry.register(peer, new ControlStreamHandle(observer));
        activeSession(store, "s1", "peer-1", Set.of(RemoteSessionCapability.VIEW_ONLY));
        SessionDuressSignalStore duressStore = new SessionDuressSignalStore(120_000L);
        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(store,
                new TrustEvidenceAssembler(emptyLedger(), OwnerTokenGate.DENY_ALL, duressStore),
                broker(), registry, operatorAuditSink(), () -> NOW, 120_000L, duressStore,
                null, null, false, 0L);

        SessionDuressSignalOutcome signal = service.recordDuressSignal("s1", "operator@x", DuressSignal.NONE);
        OperatorOutcome outcome = service.handleOperationRequest(
                new OperationRequest("s1", "op-1", RemoteOperation.SCREEN_VIEW, null));

        assertTrue(signal.accepted());
        assertFalse(signal.terminal());
        assertEquals(Kind.DENY, outcome.brokerOutcome().kind(),
                "fresh NONE should avoid KILL; DENY_ALL then denies on the normal capability gate");
        assertFalse(observer.completed, "a normal policy DENY must not kill the control stream");
    }

    @Test
    void aPositiveSessionBackedDuressSignalKillsImmediatelyAndEvicts() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        PeerIdentity peer = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());
        registry.register(peer, new ControlStreamHandle(observer));
        activeSession(store, "s1", "peer-1", Set.of(RemoteSessionCapability.VIEW_ONLY));
        SessionDuressSignalStore duressStore = new SessionDuressSignalStore(120_000L);
        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(store,
                new TrustEvidenceAssembler(emptyLedger(), OwnerTokenGate.DENY_ALL, duressStore),
                broker(), registry, operatorAuditSink(), () -> NOW, 120_000L, duressStore,
                null, null, false, 0L);

        SessionDuressSignalOutcome signal =
                service.recordDuressSignal("s1", "operator@x", DuressSignal.PANIC_SIGNAL);

        assertTrue(signal.accepted());
        assertTrue(signal.terminal());
        assertTrue(observer.sent.stream().anyMatch(Envelope::hasKill));
        assertTrue(observer.completed);
        assertTrue(store.bySessionId("s1").isEmpty());
        assertEquals(DuressSignal.AMBIGUOUS, duressStore.classify("s1", NOW),
                "terminal cleanup must not leave a reusable session id with stale duress state");
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
                assembler((sid, now) -> DuressSignal.NONE), broker(), registry, operatorAuditSink(), () -> NOW, 120_000L);

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

    @Test
    void explicitCloseOfAnActiveSessionRecordsAuditEvictsAndFreesThePeerSlot() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        PeerIdentity peer = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());
        registry.register(peer, new ControlStreamHandle(observer));
        activeSession(store, "s1", "peer-1", Set.of(RemoteSessionCapability.VIEW_ONLY));
        List<AuditEvent> closeAudits = new ArrayList<>();

        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(store,
                assembler((sid, now) -> DuressSignal.NONE), broker(), registry, closeAudits::add,
                () -> NOW, 120_000L);

        SessionOpenOutcome blocked = service.openSession(
                new SessionRequest("s2", "dev-1", "operator@x", "retry support",
                        Set.of(RemoteSessionCapability.VIEW_ONLY)),
                peer, TENANT, "Operator X");
        assertFalse(blocked.opened(), "the single-live-session guard must still block before explicit close");
        assertEquals("peer-already-has-live-session", blocked.rejectReason());

        SessionCloseOutcome close = service.closeSession("s1", "operator@x");

        assertTrue(close.accepted());
        assertTrue(store.bySessionId("s1").isEmpty(), "the closed session must be evicted");
        assertEquals(1, closeAudits.size(), "close must be durably audited before evicting");
        assertEquals("s1", closeAudits.get(0).sessionId());
        assertEquals("SESSION_CLOSE:OPERATOR", closeAudits.get(0).eventType());
        assertEquals(operatorSubjectAuditHash("operator@x"), closeAudits.get(0).contentHash());
        assertEquals(64, closeAudits.get(0).contentHash().length(),
                "close audit contentHash must remain wire-compatible SHA-256 hex");
        assertFalse(closeAudits.get(0).contentHash().contains("operator@x"),
                "the operator subject must not be written raw into the close audit event");

        SessionOpenOutcome reopened = service.openSession(
                new SessionRequest("s2", "dev-1", "operator@x", "retry support",
                        Set.of(RemoteSessionCapability.VIEW_ONLY)),
                peer, TENANT, "Operator X");
        assertTrue(reopened.opened(), "explicit close must free the peer slot for a follow-up session");
        assertEquals("s2", reopened.sessionId());
    }

    @Test
    void explicitCloseFailsClosedWhenAuditWriteFailsAndKeepsThePeerSlot() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity peer = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());
        registry.register(peer, new ControlStreamHandle(new CapturingObserver()));
        activeSession(store, "s1", "peer-1", Set.of(RemoteSessionCapability.VIEW_ONLY));
        RemoteBridgeAuditSink throwingAudit = event -> {
            throw new RuntimeException("durable audit unavailable");
        };

        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(store,
                assembler((sid, now) -> DuressSignal.NONE), broker(), registry, throwingAudit,
                () -> NOW, 120_000L);

        SessionCloseOutcome close = service.closeSession("s1", "operator@x");

        assertFalse(close.accepted());
        assertEquals("recording-failed", close.rejectReason());
        assertTrue(store.bySessionId("s1").isPresent(), "a failed close audit must not evict the live session");
        assertFalse(store.bySessionId("s1").orElseThrow().isTerminal(),
                "a failed close audit must leave the session non-terminal");

        SessionOpenOutcome blocked = service.openSession(
                new SessionRequest("s2", "dev-1", "operator@x", "retry support",
                        Set.of(RemoteSessionCapability.VIEW_ONLY)),
                peer, TENANT, "Operator X");
        assertFalse(blocked.opened(), "a failed close audit must preserve the one-live-session guard");
        assertEquals("peer-already-has-live-session", blocked.rejectReason());
    }

    @Test
    void explicitCloseRejectsWithoutAuditWhenTheSessionIsNotCloseable() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity peer = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());
        registry.register(peer, new ControlStreamHandle(new CapturingObserver()));
        store.open(new SessionRequest("s1", "dev-1", "operator@x", "support",
                        Set.of(RemoteSessionCapability.VIEW_ONLY)),
                peer, TENANT, "Operator X", NOW + 60_000L, NOW);
        List<AuditEvent> closeAudits = new ArrayList<>();

        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(store,
                assembler((sid, now) -> DuressSignal.NONE), broker(), registry, closeAudits::add,
                () -> NOW, 120_000L);

        SessionCloseOutcome close = service.closeSession("s1", "operator@x");

        assertFalse(close.accepted());
        assertEquals("session-close-refused", close.rejectReason());
        assertTrue(closeAudits.isEmpty(), "a refused close must not write a ghost close audit");
        assertTrue(store.bySessionId("s1").isPresent(), "the refused session must remain live for its owner path");
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
                assembler((sid, now) -> DuressSignal.NONE), broker(), registry, operatorAuditSink(), () -> NOW, 120_000L);

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
                assembler((sid, now) -> DuressSignal.NONE), broker(), new ControlStreamRegistry(), operatorAuditSink(), () -> NOW,
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
                assembler((sid, now) -> DuressSignal.NONE), broker(), new ControlStreamRegistry(), operatorAuditSink(), () -> NOW,
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
                assembler((sid, now) -> DuressSignal.NONE), broker(), registry, operatorAuditSink(), () -> NOW, 120_000L);

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
                assembler((sid, now) -> DuressSignal.NONE), broker(), registry, operatorAuditSink(), () -> NOW, 120_000L);

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
