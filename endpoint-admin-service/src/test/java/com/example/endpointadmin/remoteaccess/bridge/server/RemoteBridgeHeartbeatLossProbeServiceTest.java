package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.AttestationVerifier;
import com.example.endpointadmin.remoteaccess.CertTrustEvaluator;
import com.example.endpointadmin.remoteaccess.DeviceIdentityVerifier;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.Event;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.SessionRequest;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.BrokerControlPlane;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerEvidenceParser;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerTrustLedger;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeHeartbeatLossProbeService;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeHeartbeatLossProbeService.ProbeOutcome;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSession;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore;
import com.example.endpointadmin.remoteaccess.bridge.proto.Envelope;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.example.endpointadmin.remoteaccess.RemoteAccessMetrics.BRIDGE_HEARTBEAT_LOSS_PROBE_TOTAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteBridgeHeartbeatLossProbeServiceTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final long NOW = 1_000L;

    private static final StreamObserver<Envelope> QUIET_OBSERVER = new StreamObserver<>() {
        @Override public void onNext(Envelope value) { }
        @Override public void onError(Throwable t) { }
        @Override public void onCompleted() { }
    };

    private static RemoteBridgeSession open(RemoteBridgeSessionStore store,
                                            PeerIdentity peer,
                                            String sessionId,
                                            Set<RemoteSessionCapability> capabilities,
                                            boolean active) {
        RemoteBridgeSessionStore.OpenResult result = store.open(
                new SessionRequest(sessionId, "dev-1", "operator@x", "support", capabilities),
                peer, TENANT, "Operator", NOW + 60_000L, NOW);
        RemoteBridgeSession session = ((RemoteBridgeSessionStore.Opened) result).session();
        if (active) {
            assertTrue(session.transition(Event.CONSENT_GRANTED).accepted());
            assertTrue(session.transition(Event.ACTIVATE).accepted());
        }
        return session;
    }

    @Test
    void exactFaultArmedControlCloseAndKillTerminalAreBothRequiredForSuccess() throws Exception {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        PeerIdentity peer = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());
        RemoteBridgeSession session = open(store, peer, "sess-1",
                Set.of(RemoteSessionCapability.VIEW_ONLY), true);
        ControlStreamRegistry registry = new ControlStreamRegistry();
        ControlStreamHandle handle = new ControlStreamHandle(QUIET_OBSERVER);
        registry.register(peer, handle);
        ConcurrentLinkedQueue<com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.AuditEvent>
                auditEvents = new ConcurrentLinkedQueue<>();
        PeerTrustLedger ledger = new PeerTrustLedger(
                (cert, now) -> CertTrustEvaluator.TrustDecision.ALLOW,
                (evidence, now) -> AttestationVerifier.AttestationDecision.VERIFIED,
                new DeviceIdentityVerifier(Set.of(),
                        DeviceIdentityVerifier.DeviceProtectionLevel.SECURE_ELEMENT_OR_TPM),
                PeerEvidenceParser.FAIL_CLOSED, 30_000L);
        BrokerControlPlane controlPlane = new BrokerControlPlane(ledger, store, auditEvents::add, () -> NOW);
        handle.attachOnClose(() -> registry.unregister(peer, handle,
                () -> controlPlane.onControlStreamClosed(peer)));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RemoteBridgeHeartbeatLossProbeService service = new RemoteBridgeHeartbeatLossProbeService(
                registry, store, meters, () -> NOW, 1_000L, 1_000L);

        Thread watchdog = Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            handle.close();
        });
        ProbeOutcome outcome = service.exercise(session);
        watchdog.join(2_000L);

        assertTrue(outcome.terminalObserved());
        assertEquals("control-stream-loss-terminal-observed", outcome.reason());
        assertEquals(State.KILLED.name(), outcome.terminalState());
        assertTrue(outcome.probeId() != null && !outcome.probeId().isBlank());
        assertEquals(1, auditEvents.stream()
                .filter(event -> event.eventType().equals("KILLED:control-stream-lost")).count());
        assertEquals(1.0, meters.get(BRIDGE_HEARTBEAT_LOSS_PROBE_TOTAL)
                .tag("outcome", "terminal-observed").counter().count());
    }

    @Test
    void anUncorrelatedKillCannotMasqueradeAsHeartbeatLoss() throws Exception {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        PeerIdentity peer = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());
        RemoteBridgeSession session = open(store, peer, "sess-1",
                Set.of(RemoteSessionCapability.VIEW_ONLY), true);
        ControlStreamRegistry registry = new ControlStreamRegistry();
        ControlStreamHandle handle = new ControlStreamHandle(QUIET_OBSERVER);
        registry.register(peer, handle);
        handle.attachOnClose(() -> registry.unregister(peer, handle));
        RemoteBridgeHeartbeatLossProbeService service = new RemoteBridgeHeartbeatLossProbeService(
                registry, store, new SimpleMeterRegistry(), () -> NOW, 1_000L, 1_000L);

        Thread operatorKill = Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            session.transition(Event.KILL);
            registry.killPeer(peer.transportPeerKey(), session.sessionId(), "operator-close", NOW + 50L);
        });
        ProbeOutcome outcome = service.exercise(session);
        operatorKill.join(2_000L);

        assertFalse(outcome.terminalObserved());
        assertEquals("probe-cancelled-by-explicit-terminal", outcome.reason());
        assertEquals(State.KILLED.name(), outcome.terminalState());
    }

    @Test
    void refusesInactiveNonViewOnlyStaleAndDisconnectedSessions() {
        PeerIdentity peer = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());

        RemoteBridgeSessionStore inactiveStore = new RemoteBridgeSessionStore();
        RemoteBridgeSession inactive = open(inactiveStore, peer, "inactive",
                Set.of(RemoteSessionCapability.VIEW_ONLY), false);
        RemoteBridgeHeartbeatLossProbeService inactiveService = new RemoteBridgeHeartbeatLossProbeService(
                new ControlStreamRegistry(), inactiveStore, new SimpleMeterRegistry(), () -> NOW, 1_000L, 1_000L);
        assertEquals("session-not-active-view-only", inactiveService.exercise(inactive).reason());

        RemoteBridgeSessionStore ptyStore = new RemoteBridgeSessionStore();
        RemoteBridgeSession pty = open(ptyStore, peer, "pty",
                Set.of(RemoteSessionCapability.CONSTRAINED_PTY), true);
        RemoteBridgeHeartbeatLossProbeService ptyService = new RemoteBridgeHeartbeatLossProbeService(
                new ControlStreamRegistry(), ptyStore, new SimpleMeterRegistry(), () -> NOW, 1_000L, 1_000L);
        assertEquals("session-not-active-view-only", ptyService.exercise(pty).reason());

        RemoteBridgeSessionStore liveStore = new RemoteBridgeSessionStore();
        RemoteBridgeSession active = open(liveStore, peer, "active",
                Set.of(RemoteSessionCapability.VIEW_ONLY), true);
        RemoteBridgeHeartbeatLossProbeService staleService = new RemoteBridgeHeartbeatLossProbeService(
                new ControlStreamRegistry(), new RemoteBridgeSessionStore(), new SimpleMeterRegistry(),
                () -> NOW, 1_000L, 1_000L);
        assertEquals("session-not-current-peer-incarnation", staleService.exercise(active).reason());

        RemoteBridgeHeartbeatLossProbeService disconnectedService = new RemoteBridgeHeartbeatLossProbeService(
                new ControlStreamRegistry(), liveStore, new SimpleMeterRegistry(), () -> NOW, 1_000L, 1_000L);
        assertEquals("peer-not-connected-or-probe-capacity", disconnectedService.exercise(active).reason());
    }

    @Test
    void constructorEnforcesBoundedProbeWindows() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        assertThrows(IllegalArgumentException.class, () -> new RemoteBridgeHeartbeatLossProbeService(
                registry, store, meters, () -> NOW, 999L, 1_000L));
        assertThrows(IllegalArgumentException.class, () -> new RemoteBridgeHeartbeatLossProbeService(
                registry, store, meters, () -> NOW,
                RemoteBridgeHeartbeatLossProbeService.MAX_SUPPRESSION_MILLIS + 1L, 1_000L));
        assertThrows(IllegalArgumentException.class, () -> new RemoteBridgeHeartbeatLossProbeService(
                registry, store, meters, () -> NOW, 1_000L, 999L));
        assertThrows(IllegalArgumentException.class, () -> new RemoteBridgeHeartbeatLossProbeService(
                registry, store, meters, () -> NOW, 1_000L,
                RemoteBridgeHeartbeatLossProbeService.MAX_OBSERVATION_TIMEOUT_MILLIS + 1L));
    }
}
