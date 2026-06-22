package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgePermitSigner;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.Event;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.AgentErrorFrame;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeAgentErrorLedger;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeNegativeProbeService;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeNegativeProbeService.ProbeOutcome;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSession;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore;
import com.example.endpointadmin.remoteaccess.bridge.proto.Envelope;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteBridgeNegativeProbeServiceTest {

    private static final long NOW = 10_000_000L;
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final PeerIdentity PEER = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());

    private static final class CapturingObserver implements StreamObserver<Envelope> {
        final List<Envelope> sent = new ArrayList<>();

        @Override public synchronized void onNext(Envelope value) {
            sent.add(value);
        }

        @Override public void onError(Throwable t) { }

        @Override public void onCompleted() { }

        synchronized int size() {
            return sent.size();
        }

        synchronized Envelope get(int index) {
            return sent.get(index);
        }
    }

    private static RemoteBridgePermitSigner signer() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return new RemoteBridgePermitSigner(g.generateKeyPair().getPrivate(), "kid-1",
                    RemoteBridgePermitSigner.PERMIT_ALG);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RemoteBridgeSession activeSession(RemoteBridgeSessionStore store) {
        store.open(new com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.SessionRequest(
                        "sess-1", "dev-1", "operator@acik.com", "support",
                        Set.of(RemoteSessionCapability.CONSTRAINED_PTY)),
                PEER, TENANT, "Operator", NOW + 60_000L, NOW);
        RemoteBridgeSession session = store.bySessionId("sess-1").orElseThrow();
        session.transition(Event.ENABLE);
        session.transition(Event.REQUEST_SESSION);
        session.transition(Event.PROMPT_CONSENT);
        session.transition(Event.CONSENT_GRANTED);
        session.grantConsent(true, NOW + 300_000L);
        session.transition(Event.ACTIVATE);
        return session;
    }

    @Test
    void expiredPermitProbeSendsAnAlreadyExpiredSignedDispatchAndAcceptsObservedAgentDeny() throws Exception {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        RemoteBridgeSession session = activeSession(store);
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        registry.register(PEER, new ControlStreamHandle(observer));
        RemoteBridgeAgentErrorLedger ledger = new RemoteBridgeAgentErrorLedger(16);
        AtomicLong now = new AtomicLong(NOW);
        RemoteBridgeNegativeProbeService service = new RemoteBridgeNegativeProbeService(
                registry, signer(), ledger, now::get, 60_000L, 1_000L);

        CompletableFuture<ProbeOutcome> future = CompletableFuture.supplyAsync(() -> service.expiredPermit(session));
        waitForDispatch(observer);
        now.set(NOW + 10L);
        ledger.record(PEER, new AgentErrorFrame("sess-1",
                RemoteBridgeNegativeProbeService.EXPIRED_PERMIT_DENY_CODE, false, "expired"), now.get());

        ProbeOutcome outcome = future.get(2, TimeUnit.SECONDS);
        Envelope dispatch = observer.get(0);

        assertTrue(outcome.observedDeny());
        assertEquals("expired-permit-denied", outcome.reason());
        assertTrue(dispatch.hasOperationDispatch());
        assertEquals("hostname", dispatch.getOperationDispatch().getCommandLine());
        assertTrue(dispatch.getOperationDispatch().getPermit().getExpiresAtEpochMillis() < NOW,
                "the signed permit must be expired before it reaches the agent");
    }

    @Test
    void replayProbeRequiresPriorBrokerSequenceAndAcceptsObservedSeqReplay() throws Exception {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        RemoteBridgeSession session = activeSession(store);
        assertEquals(1L, session.nextSeq());
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        registry.register(PEER, new ControlStreamHandle(observer));
        RemoteBridgeAgentErrorLedger ledger = new RemoteBridgeAgentErrorLedger(16);
        AtomicLong now = new AtomicLong(NOW);
        RemoteBridgeNegativeProbeService service = new RemoteBridgeNegativeProbeService(
                registry, signer(), ledger, now::get, 60_000L, 1_000L);

        CompletableFuture<ProbeOutcome> future = CompletableFuture.supplyAsync(() -> service.replayPermit(session));
        waitForDispatch(observer);
        now.set(NOW + 10L);
        ledger.record(PEER, new AgentErrorFrame("sess-1",
                RemoteBridgeNegativeProbeService.REPLAY_DENY_CODE, false, "seq replay"), now.get());

        ProbeOutcome outcome = future.get(2, TimeUnit.SECONDS);
        Envelope dispatch = observer.get(0);

        assertTrue(outcome.observedDeny());
        assertEquals("replay-denied", outcome.reason());
        assertTrue(dispatch.hasOperationDispatch());
        assertEquals(1L, dispatch.getOperationDispatch().getPermit().getSeq(),
                "the replay probe resends the first broker sequence");
    }

    @Test
    void replayProbeRefusesBeforeAProductOperationHasAdvancedBrokerSequence() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        RemoteBridgeSession session = activeSession(store);
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        registry.register(PEER, new ControlStreamHandle(observer));
        RemoteBridgeNegativeProbeService service = new RemoteBridgeNegativeProbeService(
                registry, signer(), new RemoteBridgeAgentErrorLedger(16), () -> NOW, 60_000L, 0L);

        ProbeOutcome outcome = service.replayPermit(session);

        assertFalse(outcome.observedDeny());
        assertEquals("replay-probe-requires-prior-operation", outcome.reason());
        assertEquals(0, observer.size());
    }

    private static void waitForDispatch(CapturingObserver observer) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (observer.size() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, observer.size(), "the probe should push exactly one dispatch");
    }
}
