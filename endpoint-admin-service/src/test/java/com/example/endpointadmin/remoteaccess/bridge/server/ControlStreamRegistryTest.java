package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgePermitSigner;
import com.example.endpointadmin.remoteaccess.bridge.contract.CanonicalCommand;
import com.example.endpointadmin.remoteaccess.bridge.contract.OperationPermit;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.proto.ChannelType;
import com.example.endpointadmin.remoteaccess.bridge.proto.Envelope;
import com.example.endpointadmin.remoteaccess.bridge.proto.Heartbeat;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 T-4a-ii slice-4a — the registry's typed broker→agent CONTROL pushes (operation-permit, consent-
 * prompt). Both are NON-terminal (the stream stays open, unlike killPeer), fail-closed when the peer has no
 * live stream, and carry the correct payload + session id on the CONTROL channel.
 */
class ControlStreamRegistryTest {

    /** A capturing CONTROL StreamObserver — collects every Envelope the registry pushes. */
    private static final class CapturingObserver implements StreamObserver<Envelope> {
        final List<Envelope> sent = new ArrayList<>();
        boolean completed;

        @Override
        public void onNext(Envelope value) {
            sent.add(value);
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }

    private static final class BlockingKillObserver implements StreamObserver<Envelope> {
        final CountDownLatch killWriteEntered = new CountDownLatch(1);
        final CountDownLatch releaseKillWrite = new CountDownLatch(1);

        @Override
        public void onNext(Envelope value) {
            if (value.hasKill()) {
                killWriteEntered.countDown();
                await(releaseKillWrite);
            }
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
    }

    private static PeerIdentity peer(String key) {
        return new PeerIdentity(key, Optional.empty(), List.<X509Certificate>of());
    }

    private static PeerIdentity peerByAdComputer(String key, UUID objectGuid) {
        return new PeerIdentity(key, Optional.empty(), Optional.of(objectGuid.toString()),
                List.<X509Certificate>of());
    }

    private static ControlStreamHandle registerReady(ControlStreamRegistry registry,
                                                     String peerKey,
                                                     StreamObserver<Envelope> observer) {
        PeerIdentity identity = peer(peerKey);
        ControlStreamHandle handle = new ControlStreamHandle(observer);
        registry.register(identity, handle);
        assertTrue(registry.absorbAgentHello(identity, handle, () -> { }));
        return handle;
    }

    private static OperationPermit permit(String sessionId, String operationId) {
        return new OperationPermit(RemoteBridgePermitSigner.PERMIT_ALG, "kid-1",
                RemoteBridgePermitSigner.PERMIT_VERSION, "policy-1", sessionId + ":" + operationId,
                sessionId, operationId, "dev-1", "operator@x", RemoteSessionCapability.CONSTRAINED_PTY,
                CanonicalCommand.of("hostname").hash(), 1000L, 1300L, 0L, "sig");
    }

    private static RemoteBridgeMessages.ConsentPrompt prompt(String sessionId) {
        return new RemoteBridgeMessages.ConsentPrompt(sessionId, "Operator X", "remote support",
                Set.of(RemoteSessionCapability.VIEW_ONLY), 5000L);
    }

    private static RemoteBridgeMessages.DeviceKeyChallenge deviceKeyChallenge(String transportPeerKey) {
        return new RemoteBridgeMessages.DeviceKeyChallenge("00112233445566778899aabbccddeeff", "bm9uY2U=",
                1_000L, 9_999_999L, transportPeerKey, "device-key-session-v1");
    }

    private static RemoteBridgeMessages.AuditEvent killAck(String sessionId, long epochMillis) {
        String canonical = sessionId + "\n" + ControlStreamRegistry.EVENT_AGENT_KILL_APPLIED + "\n" + epochMillis;
        return new RemoteBridgeMessages.AuditEvent(sessionId, ControlStreamRegistry.EVENT_AGENT_KILL_APPLIED,
                sha256Hex(canonical), epochMillis);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Test
    void emergencyKillCannotDeadlockAgainstInboundGenerationDispatch() throws Exception {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity identity = peer("peer-1");
        BlockingKillObserver observer = new BlockingKillObserver();
        ControlStreamHandle handle = registerReady(registry, "peer-1", observer);
        Object lifecycleLock = new Object();
        handle.attachOnClose(() -> {
            synchronized (lifecycleLock) {
                registry.unregister(identity, handle);
            }
        });

        Thread kill = Thread.ofVirtual().start(() ->
                registry.killPeer("peer-1", "sess-1", "DURESS", 1_000L));
        assertTrue(observer.killWriteEntered.await(2, TimeUnit.SECONDS));

        CountDownLatch inboundEntered = new CountDownLatch(1);
        Thread inbound = Thread.ofVirtual().start(() -> {
            synchronized (lifecycleLock) {
                inboundEntered.countDown();
                registry.dispatchIfCurrentHelloObserved(identity, handle, () -> { });
            }
        });
        assertTrue(inboundEntered.await(2, TimeUnit.SECONDS));
        observer.releaseKillWrite.countDown();

        kill.join(2_000L);
        inbound.join(2_000L);
        assertFalse(kill.isAlive(), "emergency KILL must not wait on lifecycle/generation lock inversion");
        assertFalse(inbound.isAlive(), "inbound dispatch must not wait on lifecycle/generation lock inversion");
        assertFalse(registry.isConnected("peer-1"));
    }

    @Test
    void replacementWaitsForHeartbeatFaultCleanupBeforeSuccessorHello() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            ControlStreamRegistry registry = new ControlStreamRegistry(
                    scheduler, event -> { }, () -> 1_000L, 5_000L, 30_000L);
            PeerIdentity identity = peer("peer-1");
            ControlStreamHandle first = registerReady(registry, "peer-1", new CapturingObserver());
            assertTrue(registry.suppressHeartbeats("peer-1", "probe-1", 1_000L, 2_000L).isPresent());
            CountDownLatch cleanupEntered = new CountDownLatch(1);
            CountDownLatch releaseCleanup = new CountDownLatch(1);
            AtomicInteger cleanupCount = new AtomicInteger();
            first.attachOnClose(() -> {
                cleanupEntered.countDown();
                await(releaseCleanup);
                registry.unregister(identity, first, cleanupCount::incrementAndGet);
            });
            Thread close = Thread.ofVirtual().start(first::close);
            assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS));

            ControlStreamHandle successor = new ControlStreamHandle(new CapturingObserver());
            Thread replacement = Thread.ofVirtual().start(() -> registry.register(identity, successor));
            Thread.sleep(50L);
            assertTrue(replacement.isAlive(), "successor registration must await prior terminal cleanup");
            assertFalse(registry.absorbAgentHello(identity, successor, () -> { }),
                    "successor Hello cannot cross prior generation cleanup");

            releaseCleanup.countDown();
            close.join(2_000L);
            replacement.join(2_000L);
            assertFalse(close.isAlive());
            assertFalse(replacement.isAlive());
            assertEquals(1, cleanupCount.get());
            assertTrue(registry.absorbAgentHello(identity, successor, () -> { }));
            assertTrue(registry.isConnected("peer-1"));
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static int peerStripe(String peerKey) {
        int hash = peerKey.hashCode();
        hash ^= hash >>> 16;
        return hash & 255;
    }

    private static String collidingPeerKey(String peerKey) {
        int wanted = peerStripe(peerKey);
        for (int index = 0; index < 100_000; index++) {
            String candidate = "colliding-peer-" + index;
            if (!candidate.equals(peerKey) && peerStripe(candidate) == wanted) {
                return candidate;
            }
        }
        throw new AssertionError("no peer-lock stripe collision found");
    }

    @Test
    void sendsOperationPermitOnControlToTheLivePeer() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        PeerIdentity peer = peer("peer-1");
        ControlStreamHandle handle = new ControlStreamHandle(observer);
        registry.register(peer, handle);
        assertTrue(registry.absorbAgentHello(peer, handle, () -> { }));

        boolean sent = registry.sendOperationPermit("peer-1", permit("sess-1", "op-1"), 9_000L);

        assertTrue(sent);
        assertEquals(1, observer.sent.size());
        Envelope env = observer.sent.get(0);
        assertEquals(ChannelType.CONTROL, env.getChannelType());
        assertEquals("sess-1", env.getSessionId());
        assertTrue(env.hasOperationPermit(), "the payload must be an operation permit");
        assertEquals("op-1", env.getOperationPermit().getOperationId());
        assertFalse(observer.completed, "an operation permit must NOT close the stream (session continues)");
    }

    @Test
    void sendsOperationDispatchOnControlToTheLivePeer() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        PeerIdentity peer = peer("peer-1");
        ControlStreamHandle handle = new ControlStreamHandle(observer);
        registry.register(peer, handle);
        assertTrue(registry.absorbAgentHello(peer, handle, () -> { }));

        boolean sent = registry.sendOperationDispatch("peer-1",
                new RemoteBridgeMessages.OperationDispatch(permit("sess-1", "op-1"), "hostname"), 9_000L);

        assertTrue(sent);
        assertEquals(1, observer.sent.size());
        Envelope env = observer.sent.get(0);
        assertEquals(ChannelType.CONTROL, env.getChannelType());
        assertEquals("sess-1", env.getSessionId());
        assertTrue(env.hasOperationDispatch(), "the payload must be an operation dispatch");
        assertEquals("op-1", env.getOperationDispatch().getPermit().getOperationId());
        assertEquals("hostname", env.getOperationDispatch().getCommandLine(),
                "the plaintext command must travel with the signed permit");
        assertFalse(observer.completed, "an operation dispatch must NOT close the stream (session continues)");
    }

    @Test
    void operationDispatchFailsClosedOnNullOrUnknownPeer() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        registry.register(peer("peer-1"), new ControlStreamHandle(new CapturingObserver()));
        assertFalse(registry.sendOperationDispatch("peer-1", null, 1L), "null dispatch");
        assertFalse(registry.sendOperationDispatch("peer-1",
                new RemoteBridgeMessages.OperationDispatch(null, "hostname"), 1L), "null permit");
        assertFalse(registry.sendOperationDispatch("ghost",
                new RemoteBridgeMessages.OperationDispatch(permit("sess-1", "op-1"), "hostname"), 1L), "unknown peer");
    }

    @Test
    void sendsConsentPromptOnControlToTheLivePeer() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        PeerIdentity peer = peer("peer-1");
        ControlStreamHandle handle = new ControlStreamHandle(observer);
        registry.register(peer, handle);
        assertTrue(registry.absorbAgentHello(peer, handle, () -> { }));

        boolean sent = registry.sendConsentPrompt("peer-1", prompt("sess-1"), 9_000L);

        assertTrue(sent);
        Envelope env = observer.sent.get(0);
        assertEquals(ChannelType.CONTROL, env.getChannelType());
        assertEquals("sess-1", env.getSessionId());
        assertTrue(env.hasConsentPrompt(), "the payload must be a consent prompt");
        assertFalse(observer.completed, "a consent prompt must NOT close the stream (awaiting consent)");
    }

    @Test
    void sendsDeviceKeyChallengeOnControlToTheLivePeerCarryingTheSessionId() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        PeerIdentity peer = peer("peer-1");
        ControlStreamHandle handle = new ControlStreamHandle(observer);
        registry.register(peer, handle);
        assertTrue(registry.absorbAgentHello(peer, handle, () -> { }));

        boolean sent = registry.sendDeviceKeyChallenge("peer-1", "sess-1", deviceKeyChallenge("peer-1"), 9_000L);

        assertTrue(sent);
        Envelope env = observer.sent.get(0);
        assertEquals(ChannelType.CONTROL, env.getChannelType());
        assertEquals("sess-1", env.getSessionId(), "the broker session id rides the CONTROL envelope for correlation");
        assertTrue(env.hasDeviceKeyChallenge(), "the payload must be a device-key challenge");
        assertFalse(observer.completed, "issuing a challenge must NOT close the stream (awaiting the response)");
    }

    @Test
    void aDeviceKeyChallengeToAnUnknownPeerOrWithABlankSessionFailsClosed() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        registry.register(peer("peer-1"), new ControlStreamHandle(observer));
        assertFalse(registry.sendDeviceKeyChallenge("ghost", "sess-1", deviceKeyChallenge("ghost"), 1L),
                "unknown peer");
        assertFalse(registry.sendDeviceKeyChallenge("peer-1", "  ", deviceKeyChallenge("peer-1"), 1L),
                "blank session id");
        assertFalse(registry.sendDeviceKeyChallenge("peer-1", "sess-1", null, 1L), "null challenge");
        assertTrue(observer.sent.isEmpty(), "nothing must be pushed for a fail-closed challenge");
    }

    @Test
    void aPermitToAnUnknownPeerFailsClosed() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        assertFalse(registry.sendOperationPermit("ghost", permit("sess-1", "op-1"), 1L));
        assertFalse(registry.sendConsentPrompt("ghost", prompt("sess-1"), 1L));
    }

    @Test
    void replacementCannotReceiveOutboundAuthorityUntilItsOwnHelloIsAbsorbed() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity peer = peer("peer-1");
        ControlStreamHandle first = new ControlStreamHandle(new CapturingObserver());
        registry.register(peer, first);
        assertTrue(registry.absorbAgentHello(peer, first, () -> { }));

        CapturingObserver successorObserver = new CapturingObserver();
        ControlStreamHandle successor = new ControlStreamHandle(successorObserver);
        registry.register(peer, successor);

        assertFalse(registry.sendOperationPermit("peer-1", permit("sess-1", "op-1"), 2_000L));
        assertFalse(registry.sendOperationDispatch("peer-1",
                new RemoteBridgeMessages.OperationDispatch(permit("sess-1", "op-1"), "hostname"), 2_000L));
        assertFalse(registry.sendConsentPrompt("peer-1", prompt("sess-1"), 2_000L));
        assertFalse(registry.sendDeviceKeyChallenge(
                "peer-1", "sess-1", deviceKeyChallenge("peer-1"), 2_000L));
        assertTrue(successorObserver.sent.isEmpty());

        assertTrue(registry.absorbAgentHello(peer, successor, () -> { }));
        assertTrue(registry.sendConsentPrompt("peer-1", prompt("sess-1"), 2_001L));
        assertEquals(1, successorObserver.sent.size());
    }

    @Test
    void aNullPayloadFailsClosed() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        registry.register(peer("peer-1"), new ControlStreamHandle(observer));

        assertFalse(registry.sendOperationPermit("peer-1", null, 1L));
        assertFalse(registry.sendConsentPrompt("peer-1", null, 1L));
        assertTrue(observer.sent.isEmpty(), "nothing must be pushed for a null payload");
    }

    // ---- slice-4c-2b-2a: the connectedPeer lookup (the device→peer resolver's read), composite-consistent ----

    @Test
    void connectedPeerIsThePeerWhileRegisteredAndEmptyOtherwise() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity p = peer("peer-1");
        ControlStreamHandle handle = new ControlStreamHandle(new CapturingObserver());
        registry.register(p, handle);

        assertTrue(registry.connectedPeer("peer-1").isEmpty(), "transport alone is not product-ready");
        assertTrue(registry.absorbAgentHello(p, handle, () -> { }));
        assertEquals(p, registry.connectedPeer("peer-1").orElseThrow());
        assertTrue(registry.connectedPeer("ghost").isEmpty());
        assertTrue(registry.connectedPeer(null).isEmpty());
    }

    @Test
    void preHelloTransportIsNotConnectedAndCannotEnterAckBearingOperatorClose() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            ControlStreamRegistry registry = new ControlStreamRegistry(
                    scheduler, event -> { }, () -> 1_000L, 5_000L, 30_000L);
            CapturingObserver observer = new CapturingObserver();
            PeerIdentity identity = peer("peer-1");
            ControlStreamHandle handle = new ControlStreamHandle(observer);
            registry.register(identity, handle);

            assertFalse(registry.isConnected("peer-1"));
            assertTrue(registry.connectedPeer("peer-1").isEmpty());
            assertFalse(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", 1_000L));
            assertTrue(observer.sent.isEmpty());

            assertTrue(registry.absorbAgentHello(identity, handle, () -> { }));
            assertTrue(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", 1_001L));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void connectedPeerByAdComputerIdFindsOnlyACurrentRegisteredSanBoundPeer() {
        UUID objectGuid = UUID.fromString("44444444-4444-4444-4444-444444444444");
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity p = peerByAdComputer("peer-1", objectGuid);
        ControlStreamHandle handle = new ControlStreamHandle(new CapturingObserver());
        registry.register(p, handle);
        assertTrue(registry.absorbAgentHello(p, handle, () -> { }));

        assertEquals(p, registry.connectedPeerByAdComputerId(objectGuid.toString()).orElseThrow());
        assertEquals(p, registry.connectedPeerByAdComputerId(objectGuid.toString().toUpperCase()).orElseThrow());
        assertTrue(registry.connectedPeerByAdComputerId("not-a-guid").isEmpty());
        assertTrue(registry.connectedPeerByAdComputerId(null).isEmpty());
        assertTrue(registry.connectedPeerByAdComputerId(UUID.randomUUID().toString()).isEmpty());

        registry.unregister(p, handle);
        assertTrue(registry.connectedPeerByAdComputerId(objectGuid.toString()).isEmpty());
    }

    @Test
    void connectedPeerByAdComputerIdDoesNotMatchPeersWithoutACertSanBinding() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        registry.register(peer("peer-1"), new ControlStreamHandle(new CapturingObserver()));

        assertTrue(registry.connectedPeerByAdComputerId("44444444-4444-4444-4444-444444444444").isEmpty());
    }

    @Test
    void unregisterWithTheCurrentHandleClearsTheConnectedPeer() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity p = peer("peer-1");
        ControlStreamHandle handle = new ControlStreamHandle(new CapturingObserver());
        registry.register(p, handle);

        registry.unregister(p, handle);
        assertTrue(registry.connectedPeer("peer-1").isEmpty());
    }

    @Test
    void unregisterWithAStaleHandleDoesNotEvictTheSuccessor() {
        // a reconnect REPLACED the stream; the old stream's late unregister must not remove its successor
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity p = peer("peer-1");
        ControlStreamHandle first = new ControlStreamHandle(new CapturingObserver());
        ControlStreamHandle second = new ControlStreamHandle(new CapturingObserver());
        registry.register(p, first);
        registry.register(p, second); // replace
        assertTrue(registry.absorbAgentHello(p, second, () -> { }));
        assertFalse(registry.absorbAgentHello(p, first, () -> { }),
                "a stale handle must not ready its successor");

        registry.unregister(p, first); // stale handle
        assertTrue(registry.connectedPeer("peer-1").isPresent(), "the successor stream must survive a stale unregister");
    }

    @Test
    void normalReplacementRunsPredecessorCleanupOnceBeforeSuccessorHello() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity p = peer("peer-1");
        ControlStreamHandle first = new ControlStreamHandle(new CapturingObserver());
        ControlStreamHandle successor = new ControlStreamHandle(new CapturingObserver());
        AtomicInteger cleanups = new AtomicInteger();
        registry.register(p, first);
        first.attachOnClose(() -> registry.unregister(p, first, cleanups::incrementAndGet));

        registry.register(p, successor);

        assertTrue(first.isClosed());
        assertEquals(1, cleanups.get(), "replacement must retire predecessor session/trust state exactly once");
        assertTrue(registry.absorbAgentHello(p, successor, () -> { }));
        assertEquals(p, registry.connectedPeer("peer-1").orElseThrow());
        registry.unregister(p, first, cleanups::incrementAndGet);
        assertEquals(1, cleanups.get(), "a late predecessor callback cannot repeat terminal cleanup");
        assertEquals(p, registry.connectedPeer("peer-1").orElseThrow());
    }

    @Test
    void transportLossCallbackCompletesBeforeSamePeerReconnectCanRegister() throws Exception {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity p = peer("peer-1");
        ControlStreamHandle first = new ControlStreamHandle(new CapturingObserver());
        ControlStreamHandle successor = new ControlStreamHandle(new CapturingObserver());
        registry.register(p, first);

        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch successorRegistered = new CountDownLatch(1);
        Thread unregister = Thread.ofPlatform().start(() -> registry.unregister(p, first, () -> {
            callbackEntered.countDown();
            try {
                releaseCallback.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }));
        assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
        assertTrue(registry.connectedPeer("peer-1").isEmpty(),
                "the dead slot must be absent while terminal cleanup is still fencing reconnect");

        Thread reconnect = Thread.ofPlatform().start(() -> {
            registry.register(p, successor);
            registry.absorbAgentHello(p, successor, () -> { });
            successorRegistered.countDown();
        });
        assertFalse(successorRegistered.await(100, TimeUnit.MILLISECONDS),
                "same-peer successor must wait until transport-loss session cleanup finishes");

        releaseCallback.countDown();
        unregister.join(2_000);
        reconnect.join(2_000);

        assertFalse(unregister.isAlive());
        assertFalse(reconnect.isAlive());
        assertEquals(0, successorRegistered.getCount());
        assertEquals(p, registry.connectedPeer("peer-1").orElseThrow());
    }

    @Test
    void inboundMutationCompletesBeforeSamePeerReconnectCanReplaceItsHandle() throws Exception {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity p = peer("peer-1");
        ControlStreamHandle first = new ControlStreamHandle(new CapturingObserver());
        ControlStreamHandle successor = new ControlStreamHandle(new CapturingObserver());
        registry.register(p, first);
        assertTrue(registry.absorbAgentHello(p, first, () -> { }));

        CountDownLatch mutationEntered = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        CountDownLatch successorRegistered = new CountDownLatch(1);
        Thread inbound = Thread.ofPlatform().start(() -> registry.dispatchIfCurrentHelloObserved(p, first, () -> {
            mutationEntered.countDown();
            await(releaseMutation);
        }));
        assertTrue(mutationEntered.await(2, TimeUnit.SECONDS));

        Thread reconnect = Thread.ofPlatform().start(() -> {
            registry.register(p, successor);
            successorRegistered.countDown();
        });
        assertFalse(successorRegistered.await(100, TimeUnit.MILLISECONDS),
                "same-peer replacement must wait for the current generation's inbound mutation");

        releaseMutation.countDown();
        inbound.join(2_000);
        reconnect.join(2_000);

        assertFalse(inbound.isAlive());
        assertFalse(reconnect.isAlive());
        assertEquals(0, successorRegistered.getCount());
        assertTrue(registry.connectedPeer("peer-1").isEmpty(),
                "the successor remains unready until it supplies its own AgentHello");
        assertFalse(registry.dispatchIfCurrentHelloObserved(p, first, () -> {
            throw new AssertionError("stale mutation dispatched");
        }));
    }

    @Test
    void aThirdReconnectCannotReplaceAnInitializingSuccessor() throws Exception {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity p = peer("peer-1");
        ControlStreamHandle first = new ControlStreamHandle(new CapturingObserver());
        ControlStreamHandle second = new ControlStreamHandle(new CapturingObserver());
        ControlStreamHandle third = new ControlStreamHandle(new CapturingObserver());
        registry.register(p, first);
        assertTrue(registry.absorbAgentHello(p, first, () -> { }));

        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        first.attachOnClose(() -> {
            cleanupEntered.countDown();
            await(releaseCleanup);
        });

        CountDownLatch secondRegistered = new CountDownLatch(1);
        Thread secondReconnect = Thread.ofPlatform().start(() -> {
            registry.register(p, second);
            secondRegistered.countDown();
        });
        assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS));

        CountDownLatch thirdRegistered = new CountDownLatch(1);
        Thread thirdReconnect = Thread.ofPlatform().start(() -> {
            registry.register(p, third);
            thirdRegistered.countDown();
        });
        assertFalse(thirdRegistered.await(100, TimeUnit.MILLISECONDS),
                "a third reconnect must not replace a successor whose predecessor cleanup is incomplete");
        assertFalse(registry.absorbAgentHello(p, third, () -> { }));

        releaseCleanup.countDown();
        secondReconnect.join(2_000L);
        thirdReconnect.join(2_000L);

        assertFalse(secondReconnect.isAlive());
        assertFalse(thirdReconnect.isAlive());
        assertEquals(0, secondRegistered.getCount());
        assertEquals(0, thirdRegistered.getCount());
        assertTrue(second.isClosed(), "the serialized third reconnect replaces the fully initialized second handle");
        assertFalse(registry.absorbAgentHello(p, second, () -> { }));
        assertTrue(registry.absorbAgentHello(p, third, () -> { }));
        assertEquals(p, registry.connectedPeer("peer-1").orElseThrow());
    }

    @Test
    void outboundAuthorityCannotCrossAConcurrentGenerationReplacement() throws Exception {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity identity = peer("peer-1");
        CountDownLatch outboundEntered = new CountDownLatch(1);
        CountDownLatch releaseOutbound = new CountDownLatch(1);
        List<Envelope> oldDeliveries = new ArrayList<>();
        StreamObserver<Envelope> blockingObserver = new StreamObserver<>() {
            @Override
            public void onNext(Envelope value) {
                outboundEntered.countDown();
                await(releaseOutbound);
                oldDeliveries.add(value);
            }

            @Override public void onError(Throwable failure) { }
            @Override public void onCompleted() { }
        };
        ControlStreamHandle first = registerReady(registry, "peer-1", blockingObserver);

        AtomicReference<Boolean> sent = new AtomicReference<>();
        Thread outbound = Thread.ofPlatform().start(() ->
                sent.set(registry.sendConsentPrompt("peer-1", prompt("sess-1"), 2_000L)));
        assertTrue(outboundEntered.await(2, TimeUnit.SECONDS));

        CapturingObserver successorObserver = new CapturingObserver();
        ControlStreamHandle successor = new ControlStreamHandle(successorObserver);
        CountDownLatch replacementFinished = new CountDownLatch(1);
        Thread replacement = Thread.ofPlatform().start(() -> {
            registry.register(identity, successor);
            replacementFinished.countDown();
        });
        assertFalse(replacementFinished.await(100, TimeUnit.MILLISECONDS),
                "replacement must not cross an in-flight authority delivery for the old generation");

        releaseOutbound.countDown();
        outbound.join(2_000L);
        replacement.join(2_000L);

        assertEquals(Boolean.TRUE, sent.get());
        assertEquals(1, oldDeliveries.size());
        assertTrue(first.isClosed());
        assertTrue(successorObserver.sent.isEmpty());
        assertFalse(registry.sendConsentPrompt("peer-1", prompt("sess-2"), 2_001L),
                "the successor remains authority-ineligible until its own Hello is absorbed");
    }

    @Test
    void stalledPeerCallbackCannotDelayEmergencyKillForACollidingPeerStripe() throws Exception {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        String firstKey = "peer-1";
        String secondKey = collidingPeerKey(firstKey);
        assertEquals(peerStripe(firstKey), peerStripe(secondKey));

        PeerIdentity firstPeer = peer(firstKey);
        ControlStreamHandle firstHandle = registerReady(registry, firstKey, new CapturingObserver());
        CapturingObserver secondObserver = new CapturingObserver();
        registerReady(registry, secondKey, secondObserver);

        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        Thread blockedCallback = Thread.ofPlatform().start(() ->
                registry.dispatchIfCurrentHelloObserved(firstPeer, firstHandle, () -> {
                    callbackEntered.countDown();
                    await(releaseCallback);
                }));
        assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));

        AtomicReference<Boolean> killed = new AtomicReference<>();
        Thread emergencyKill = Thread.ofPlatform().start(() ->
                killed.set(registry.killPeer(secondKey, "sess-2", "DURESS", 3_000L)));
        emergencyKill.join(500L);

        assertFalse(emergencyKill.isAlive(),
                "an unrelated peer sharing the legacy stripe cannot delay emergency termination");
        assertEquals(Boolean.TRUE, killed.get());
        assertTrue(secondObserver.completed);

        releaseCallback.countDown();
        blockedCallback.join(2_000L);
        assertFalse(blockedCallback.isAlive());
    }

    @Test
    void aThrowingTransportLossCallbackCannotLeaveTheDeadStreamRegistered() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity p = peer("peer-1");
        ControlStreamHandle handle = new ControlStreamHandle(new CapturingObserver());
        registry.register(p, handle);

        assertThrows(IllegalStateException.class,
                () -> registry.unregister(p, handle, () -> {
                    throw new IllegalStateException("cleanup failed");
                }));

        assertTrue(registry.connectedPeer("peer-1").isEmpty());
    }

    @Test
    void killPeerClearsTheConnectedPeer() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity p = peer("peer-1");
        registry.register(p, new ControlStreamHandle(new CapturingObserver()));

        assertTrue(registry.killPeer("peer-1", "sess-1", "duress", 1L));
        assertTrue(registry.connectedPeer("peer-1").isEmpty());
    }

    @Test
    void killPeerReportsDeliveryFailureButStillClosesAndUnregistersDeadStream() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity p = peer("peer-1");
        StreamObserver<Envelope> deadObserver = new StreamObserver<>() {
            @Override public void onNext(Envelope value) { throw new IllegalStateException("dead stream"); }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        };
        registry.register(p, new ControlStreamHandle(deadObserver));

        assertFalse(registry.killPeer("peer-1", "sess-1", "operator-close", 1L));
        assertTrue(registry.connectedPeer("peer-1").isEmpty(),
                "a failed send must still remove the dead control stream");
    }

    @Test
    void heartbeatSuppressionIsPeerScopedAndGeneralControlStillFlows() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver first = new CapturingObserver();
        CapturingObserver second = new CapturingObserver();
        ControlStreamHandle firstHandle = new ControlStreamHandle(first);
        ControlStreamHandle secondHandle = new ControlStreamHandle(second);
        registry.register(peer("peer-1"), firstHandle);
        registry.register(peer("peer-2"), secondHandle);

        ControlStreamRegistry.HeartbeatSuppressionTicket ticket = registry.suppressHeartbeats(
                "peer-1", "probe-1", 1_000L, 2_000L).orElseThrow();

        Envelope heartbeat = Envelope.newBuilder().setChannelType(ChannelType.CONTROL)
                .setHeartbeat(Heartbeat.newBuilder().setHeartbeatIntervalMillis(1_000L)).build();
        assertFalse(firstHandle.sendHeartbeat(heartbeat, 1_500L));
        assertTrue(firstHandle.send(Envelope.getDefaultInstance()), "KILL/permit/general CONTROL stays live");
        assertTrue(secondHandle.sendHeartbeat(heartbeat, 1_500L),
                "another peer is never suppressed");
        assertEquals("probe-1", ticket.probeId());
        assertEquals(1, first.sent.size());
        assertEquals(1, second.sent.size());
    }

    @Test
    void faultArmedReconnectReportsTheOldHandleLossOnceAndPreservesTheSuccessor() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity p = peer("peer-1");
        ControlStreamHandle first = new ControlStreamHandle(new CapturingObserver());
        AtomicInteger terminalCleanups = new AtomicInteger();
        registry.register(p, first);
        first.attachOnClose(() -> registry.unregister(p, first, terminalCleanups::incrementAndGet));
        ControlStreamRegistry.HeartbeatSuppressionTicket ticket = registry.suppressHeartbeats(
                "peer-1", "probe-1", 1_000L, 2_000L).orElseThrow();

        ControlStreamHandle successor = new ControlStreamHandle(new CapturingObserver());
        registry.register(p, successor);
        assertTrue(registry.absorbAgentHello(p, successor, () -> { }));
        successor.attachOnClose(() -> registry.unregister(p, successor, terminalCleanups::incrementAndGet));

        assertTrue(first.isClosed());
        assertTrue(registry.heartbeatFaultControlStreamClosed(ticket.probeId()));
        assertEquals(1, terminalCleanups.get());
        assertEquals(p, registry.connectedPeer("peer-1").orElseThrow(),
                "the replacement transport remains available for a future session");
    }

    @Test
    void explicitKillCancelsFaultCorrelationInsteadOfFakingHeartbeatLoss() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity p = peer("peer-1");
        ControlStreamHandle handle = new ControlStreamHandle(new CapturingObserver());
        registry.register(p, handle);
        handle.attachOnClose(() -> registry.unregister(p, handle));
        ControlStreamRegistry.HeartbeatSuppressionTicket ticket = registry.suppressHeartbeats(
                "peer-1", "probe-1", 1_000L, 2_000L).orElseThrow();

        assertTrue(registry.killPeer("peer-1", "sess-1", "operator-close", 1_100L));

        assertFalse(registry.heartbeatFaultControlStreamClosed(ticket.probeId()));
        assertEquals(ControlStreamRegistry.HeartbeatFaultStatus.CANCELLED,
                registry.heartbeatFaultStatus(ticket.probeId()).orElseThrow());
    }

    @Test
    void operatorKillWaitsForExactAgentAckThenClosesAndDurablyRecords() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicLong now = new AtomicLong(1_000L);
            ConcurrentLinkedQueue<RemoteBridgeMessages.AuditEvent> audits = new ConcurrentLinkedQueue<>();
            ControlStreamRegistry registry = new ControlStreamRegistry(
                    scheduler, audits::add, now::get, 5_000L, 30_000L);
            CapturingObserver observer = new CapturingObserver();
            ControlStreamHandle handle = registerReady(registry, "peer-1", observer);
            handle.attachOnClose(() -> registry.unregister(peer("peer-1"), handle));

            assertTrue(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", 1_000L));
            assertEquals(1, observer.sent.size());
            assertTrue(observer.sent.get(0).hasKill());
            assertEquals("OPERATOR_CLOSE", observer.sent.get(0).getKill().getKillReason());
            assertFalse(handle.isClosed(), "the exact CONTROL generation stays open only for its bounded ACK");

            now.set(1_010L);
            assertEquals(ControlStreamRegistry.OperatorKillAckResult.ACKNOWLEDGED,
                    registry.acceptOperatorKillAcknowledgement(peer("peer-1"), killAck("sess-1", 1_005L), 1_010L));

            assertTrue(handle.isClosed());
            assertTrue(observer.completed);
            assertFalse(registry.isConnected("peer-1"));
            assertEquals(List.of("SESSION_CLOSE:AGENT_KILL_APPLIED"),
                    audits.stream().map(RemoteBridgeMessages.AuditEvent::eventType).toList());
            assertEquals(ControlStreamRegistry.OperatorKillAckResult.REFUSED_NO_PENDING,
                    registry.acceptOperatorKillAcknowledgement(peer("peer-1"), killAck("sess-1", 1_006L), 1_011L));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void pendingOperatorKillQuarantinesEveryAuthorityBearingControlPush() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            ControlStreamRegistry registry = new ControlStreamRegistry(
                    scheduler, event -> { }, () -> 5_000L, 5_000L, 30_000L);
            CapturingObserver observer = new CapturingObserver();
            registerReady(registry, "peer-1", observer);
            assertTrue(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", 1_000L));

            assertFalse(registry.isConnected("peer-1"));
            assertTrue(registry.connectedPeer("peer-1").isEmpty());
            assertFalse(registry.sendOperationPermit("peer-1", permit("sess-2", "op-1"), 5_001L));
            assertFalse(registry.sendOperationDispatch("peer-1",
                    new RemoteBridgeMessages.OperationDispatch(permit("sess-2", "op-1"), "hostname"), 5_001L));
            assertFalse(registry.sendConsentPrompt("peer-1", prompt("sess-2"), 5_001L));
            assertFalse(registry.sendDeviceKeyChallenge("peer-1", "sess-2",
                    deviceKeyChallenge("peer-1"), 5_001L));
            assertEquals(1, observer.sent.size(), "only the original OPERATOR_CLOSE KILL may use the quarantine");
            assertTrue(observer.sent.get(0).hasKill());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void emergencyKillSupersedesPendingOperatorAckWithoutLeavingCorrelationState() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            ConcurrentLinkedQueue<RemoteBridgeMessages.AuditEvent> audits = new ConcurrentLinkedQueue<>();
            ControlStreamRegistry registry = new ControlStreamRegistry(
                    scheduler, audits::add, () -> 6_000L, 5_000L, 30_000L);
            CapturingObserver observer = new CapturingObserver();
            registerReady(registry, "peer-1", observer);
            assertTrue(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", 1_000L));

            assertTrue(registry.killPeer("peer-1", "sess-1", "DURESS", 6_000L));

            assertTrue(observer.completed);
            assertEquals(2, observer.sent.stream().filter(Envelope::hasKill).count());
            assertEquals(List.of("SESSION_CLOSE:AGENT_KILL_ACK_STREAM_CLOSED"),
                    audits.stream().map(RemoteBridgeMessages.AuditEvent::eventType).toList());
            assertEquals(ControlStreamRegistry.OperatorKillAckResult.REFUSED_NO_PENDING,
                    registry.acceptOperatorKillAcknowledgement(peer("peer-1"), killAck("sess-1", 6_001L), 6_002L));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void emergencyKillAndCloseCompleteBeforeAStalledPendingAckAudit() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch releaseAudit = new CountDownLatch(1);
        try {
            CountDownLatch auditEntered = new CountDownLatch(1);
            ControlStreamRegistry registry = new ControlStreamRegistry(scheduler, event -> {
                auditEntered.countDown();
                await(releaseAudit);
            }, () -> 7_000L, 5_000L, 30_000L);
            CapturingObserver observer = new CapturingObserver();
            registerReady(registry, "peer-1", observer);
            assertTrue(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", 1_000L));
            AtomicReference<Boolean> delivered = new AtomicReference<>();

            Thread emergency = Thread.ofPlatform().start(() ->
                    delivered.set(registry.killPeer("peer-1", "sess-1", "DURESS", 7_000L)));
            assertTrue(auditEntered.await(2, TimeUnit.SECONDS));

            assertTrue(observer.completed,
                    "emergency handle close must not wait for the pending-ACK durable audit");
            assertEquals(2, observer.sent.stream().filter(Envelope::hasKill).count());
            assertEquals("DURESS", observer.sent.get(1).getKill().getKillReason());
            assertTrue(emergency.isAlive(), "only the post-close audit is intentionally stalled");

            releaseAudit.countDown();
            emergency.join(2_000L);
            assertFalse(emergency.isAlive());
            assertEquals(Boolean.TRUE, delivered.get());
        } finally {
            releaseAudit.countDown();
            scheduler.shutdownNow();
        }
    }

    @Test
    void wrongPeerSessionHashAndReplayCannotConsumePendingOperatorKill() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicLong now = new AtomicLong(10_000L);
            ConcurrentLinkedQueue<RemoteBridgeMessages.AuditEvent> audits = new ConcurrentLinkedQueue<>();
            ControlStreamRegistry registry = new ControlStreamRegistry(
                    scheduler, audits::add, now::get, 5_000L, 30_000L);
            CapturingObserver observer = new CapturingObserver();
            registerReady(registry, "peer-1", observer);
            assertTrue(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", 10_000L));

            assertEquals(ControlStreamRegistry.OperatorKillAckResult.REFUSED_NO_PENDING,
                    registry.acceptOperatorKillAcknowledgement(peer("peer-2"), killAck("sess-1", 10_001L), 10_002L));
            assertEquals(ControlStreamRegistry.OperatorKillAckResult.REFUSED_WRONG_SESSION,
                    registry.acceptOperatorKillAcknowledgement(peer("peer-1"), killAck("sess-2", 10_001L), 10_002L));
            assertEquals(ControlStreamRegistry.OperatorKillAckResult.REFUSED_INVALID_PROVENANCE,
                    registry.acceptOperatorKillAcknowledgement(peer("peer-1"),
                            new RemoteBridgeMessages.AuditEvent("sess-1",
                                    ControlStreamRegistry.EVENT_AGENT_KILL_APPLIED, "0".repeat(64), 10_001L), 10_002L));
            assertEquals(ControlStreamRegistry.OperatorKillAckResult.REFUSED_OUTSIDE_FRESHNESS_WINDOW,
                    registry.acceptOperatorKillAcknowledgement(peer("peer-1"), killAck("sess-1", 9_999L), 10_002L));
            assertFalse(observer.completed);
            assertTrue(audits.isEmpty());

            assertEquals(ControlStreamRegistry.OperatorKillAckResult.ACKNOWLEDGED,
                    registry.acceptOperatorKillAcknowledgement(peer("peer-1"), killAck("sess-1", 10_003L), 10_004L));
            assertTrue(observer.completed);
            assertEquals(1, audits.size());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void operatorKillAckTimeoutClosesOnlyTheArmedHandleAndRecordsFailure() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicLong now = new AtomicLong(20_100L);
            ConcurrentLinkedQueue<RemoteBridgeMessages.AuditEvent> audits = new ConcurrentLinkedQueue<>();
            CountDownLatch recorded = new CountDownLatch(1);
            ControlStreamRegistry registry = new ControlStreamRegistry(scheduler, event -> {
                audits.add(event);
                recorded.countDown();
            }, now::get, 25L, 30_000L);
            CapturingObserver observer = new CapturingObserver();
            registerReady(registry, "peer-1", observer);

            assertTrue(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", 20_000L));
            assertTrue(recorded.await(2, TimeUnit.SECONDS));

            assertTrue(observer.completed);
            assertFalse(registry.isConnected("peer-1"));
            assertEquals(List.of("SESSION_CLOSE:AGENT_KILL_ACK_TIMEOUT"),
                    audits.stream().map(RemoteBridgeMessages.AuditEvent::eventType).toList());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void operatorKillAckTimeoutClosesTheHandleBeforeDurableAuditCanBlock() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch releaseAudit = new CountDownLatch(1);
        try {
            CountDownLatch auditEntered = new CountDownLatch(1);
            ControlStreamRegistry registry = new ControlStreamRegistry(scheduler, event -> {
                auditEntered.countDown();
                await(releaseAudit);
            }, () -> 25_100L, 25L, 30_000L);
            CapturingObserver observer = new CapturingObserver();
            ControlStreamHandle handle = registerReady(registry, "peer-1", observer);
            handle.attachOnClose(() -> registry.unregister(peer("peer-1"), handle));

            assertTrue(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", 25_000L));
            assertTrue(auditEntered.await(2, TimeUnit.SECONDS));

            assertTrue(handle.isClosed(), "timeout must close the exact handle before durable audit I/O");
            assertTrue(observer.completed);
            assertFalse(registry.isConnected("peer-1"));
        } finally {
            releaseAudit.countDown();
            scheduler.shutdownNow();
        }
    }

    @Test
    void reconnectFailsPendingAckButNeverClosesTheSuccessor() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicLong now = new AtomicLong(30_010L);
            ConcurrentLinkedQueue<RemoteBridgeMessages.AuditEvent> audits = new ConcurrentLinkedQueue<>();
            ControlStreamRegistry registry = new ControlStreamRegistry(
                    scheduler, audits::add, now::get, 5_000L, 30_000L);
            CapturingObserver oldObserver = new CapturingObserver();
            registerReady(registry, "peer-1", oldObserver);
            assertTrue(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", 30_000L));

            CapturingObserver successorObserver = new CapturingObserver();
            registerReady(registry, "peer-1", successorObserver);

            assertTrue(oldObserver.completed);
            assertFalse(successorObserver.completed);
            assertTrue(registry.isConnected("peer-1"));
            assertEquals(List.of("SESSION_CLOSE:AGENT_KILL_ACK_STREAM_REPLACED"),
                    audits.stream().map(RemoteBridgeMessages.AuditEvent::eventType).toList());
            assertEquals(ControlStreamRegistry.OperatorKillAckResult.REFUSED_NO_PENDING,
                    registry.acceptOperatorKillAcknowledgement(peer("peer-1"), killAck("sess-1", 30_005L), 30_010L));
            assertFalse(successorObserver.completed);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void reconnectClosesThePredecessorBeforeStreamReplacedAuditCanBlock() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch releaseAudit = new CountDownLatch(1);
        try {
            CountDownLatch auditEntered = new CountDownLatch(1);
            ControlStreamRegistry registry = new ControlStreamRegistry(scheduler, event -> {
                auditEntered.countDown();
                await(releaseAudit);
            }, () -> 35_010L, 5_000L, 30_000L);
            CapturingObserver oldObserver = new CapturingObserver();
            ControlStreamHandle oldHandle = registerReady(registry, "peer-1", oldObserver);
            oldHandle.attachOnClose(() -> registry.unregister(peer("peer-1"), oldHandle));
            assertTrue(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", 35_000L));

            CapturingObserver successorObserver = new CapturingObserver();
            ControlStreamHandle successor = new ControlStreamHandle(successorObserver);
            Thread reconnect = Thread.ofPlatform().start(() -> registry.register(peer("peer-1"), successor));
            assertTrue(auditEntered.await(2, TimeUnit.SECONDS));

            assertTrue(oldHandle.isClosed(), "replacement must close the predecessor before durable audit I/O");
            assertTrue(oldObserver.completed);
            assertTrue(reconnect.isAlive(), "only the durable audit is intentionally blocked");
            assertTrue(registry.connectedPeer("peer-1").isEmpty(),
                    "the successor cannot publish authority while replacement audit is blocked");

            releaseAudit.countDown();
            reconnect.join(2_000L);
            assertFalse(reconnect.isAlive());
            assertTrue(registry.absorbAgentHello(peer("peer-1"), successor, () -> { }));
            assertFalse(successorObserver.completed);
        } finally {
            releaseAudit.countDown();
            scheduler.shutdownNow();
        }
    }

    @Test
    void durableAckAuditFailureNeverLeavesTheTransportOrPendingClaimOpen() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            ControlStreamRegistry registry = new ControlStreamRegistry(scheduler, event -> {
                throw new IllegalStateException("durable recorder unavailable");
            }, () -> 40_010L, 5_000L, 30_000L);
            CapturingObserver observer = new CapturingObserver();
            registerReady(registry, "peer-1", observer);
            assertTrue(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", 40_000L));

            assertEquals(ControlStreamRegistry.OperatorKillAckResult.ACKNOWLEDGED_AUDIT_FAILED,
                    registry.acceptOperatorKillAcknowledgement(peer("peer-1"), killAck("sess-1", 40_010L), 40_010L));
            assertTrue(observer.completed);
            assertFalse(registry.isConnected("peer-1"));
            assertTrue(registry.operatorKillAckAuditFailureLatched(),
                    "acceptance must remain failed for this pod lifetime after a durable write failure");
            assertEquals(ControlStreamRegistry.OperatorKillAckResult.REFUSED_NO_PENDING,
                    registry.acceptOperatorKillAcknowledgement(peer("peer-1"), killAck("sess-1", 40_006L), 40_011L));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void acknowledgedKillClosesTheExactHandleBeforeDurableAuditCanBlock() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch releaseAudit = new CountDownLatch(1);
        try {
            CountDownLatch auditEntered = new CountDownLatch(1);
            ControlStreamRegistry registry = new ControlStreamRegistry(scheduler, event -> {
                auditEntered.countDown();
                await(releaseAudit);
            }, () -> 45_010L, 5_000L, 30_000L);
            CapturingObserver observer = new CapturingObserver();
            ControlStreamHandle handle = registerReady(registry, "peer-1", observer);
            handle.attachOnClose(() -> registry.unregister(peer("peer-1"), handle));
            assertTrue(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", 45_000L));
            AtomicReference<ControlStreamRegistry.OperatorKillAckResult> result = new AtomicReference<>();

            Thread acknowledgement = Thread.ofPlatform().start(() -> result.set(
                    registry.acceptOperatorKillAcknowledgement(
                            peer("peer-1"), killAck("sess-1", 45_010L), 45_010L)));
            assertTrue(auditEntered.await(2, TimeUnit.SECONDS),
                    () -> "ACK did not reach audit: result=" + result.get()
                            + " threadState=" + acknowledgement.getState()
                            + " handleClosed=" + handle.isClosed());

            assertTrue(handle.isClosed(), "the consumed ACK generation must close before durable audit I/O");
            assertTrue(observer.completed);
            assertFalse(registry.isConnected("peer-1"));
            assertFalse(registry.killPeer("peer-1", "sess-1", "DURESS", 45_011L),
                    "no unregistered live CONTROL handle may survive the blocked audit");
            assertTrue(acknowledgement.isAlive(), "only the durable audit is intentionally blocked");

            releaseAudit.countDown();
            acknowledgement.join(2_000L);
            assertFalse(acknowledgement.isAlive());
            assertEquals(ControlStreamRegistry.OperatorKillAckResult.ACKNOWLEDGED, result.get());
        } finally {
            releaseAudit.countDown();
            scheduler.shutdownNow();
        }
    }

    @Test
    void failedOperatorKillWriteCannotLeaveAClosedHandleOrPendingAckRegistered() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            ConcurrentLinkedQueue<RemoteBridgeMessages.AuditEvent> audits = new ConcurrentLinkedQueue<>();
            ControlStreamRegistry registry = new ControlStreamRegistry(
                    scheduler, audits::add, () -> 50_010L, 5_000L, 30_000L);
            StreamObserver<Envelope> deadObserver = new StreamObserver<>() {
                @Override public void onNext(Envelope value) { throw new IllegalStateException("dead stream"); }
                @Override public void onError(Throwable t) { }
                @Override public void onCompleted() { }
            };
            registerReady(registry, "peer-1", deadObserver);

            assertFalse(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", 50_000L));

            assertFalse(registry.isConnected("peer-1"));
            assertEquals(List.of("SESSION_CLOSE:AGENT_KILL_ACK_SEND_FAILED"),
                    audits.stream().map(RemoteBridgeMessages.AuditEvent::eventType).toList());
            assertEquals(ControlStreamRegistry.OperatorKillAckResult.REFUSED_NO_PENDING,
                    registry.acceptOperatorKillAcknowledgement(peer("peer-1"), killAck("sess-1", 50_005L), 50_010L));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void productionStyleOnCloseOwnsFailedSendAndRecordsOneStreamClosedOutcome() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            ConcurrentLinkedQueue<RemoteBridgeMessages.AuditEvent> audits = new ConcurrentLinkedQueue<>();
            ControlStreamRegistry registry = new ControlStreamRegistry(
                    scheduler, audits::add, () -> 55_010L, 5_000L, 30_000L);
            PeerIdentity p = peer("peer-1");
            StreamObserver<Envelope> deadObserver = new StreamObserver<>() {
                @Override public void onNext(Envelope value) { throw new IllegalStateException("dead stream"); }
                @Override public void onError(Throwable t) { }
                @Override public void onCompleted() { }
            };
            ControlStreamHandle handle = new ControlStreamHandle(deadObserver);
            registry.register(p, handle);
            assertTrue(registry.absorbAgentHello(p, handle, () -> { }));
            handle.attachOnClose(() -> registry.unregister(p, handle));

            assertFalse(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", 55_000L));

            assertFalse(registry.isConnected("peer-1"));
            assertEquals(List.of("SESSION_CLOSE:AGENT_KILL_ACK_STREAM_CLOSED"),
                    audits.stream().map(RemoteBridgeMessages.AuditEvent::eventType).toList());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void duplicateOperatorCloseCannotReplaceOrExtendTheExistingPendingAck() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            ConcurrentLinkedQueue<RemoteBridgeMessages.AuditEvent> audits = new ConcurrentLinkedQueue<>();
            ControlStreamRegistry registry = new ControlStreamRegistry(
                    scheduler, audits::add, () -> 60_010L, 5_000L, 30_000L);
            CapturingObserver observer = new CapturingObserver();
            registerReady(registry, "peer-1", observer);

            assertTrue(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", 60_000L));
            assertFalse(registry.killPeerAwaitingOperatorAck("peer-1", "sess-2", 60_001L));
            assertEquals(1, observer.sent.stream().filter(Envelope::hasKill).count());
            assertEquals("sess-1", observer.sent.get(0).getKill().getSessionId());

            assertEquals(ControlStreamRegistry.OperatorKillAckResult.ACKNOWLEDGED,
                    registry.acceptOperatorKillAcknowledgement(peer("peer-1"), killAck("sess-1", 60_010L), 60_010L));
            assertEquals(List.of("SESSION_CLOSE:AGENT_KILL_APPLIED"),
                    audits.stream().map(RemoteBridgeMessages.AuditEvent::eventType).toList());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void serverShutdownCancelsPendingAckClosesHandleAndRecordsWhy() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            ConcurrentLinkedQueue<RemoteBridgeMessages.AuditEvent> audits = new ConcurrentLinkedQueue<>();
            CapturingObserver observer = new CapturingObserver();
            ControlStreamRegistry registry = new ControlStreamRegistry(
                    scheduler, audits::add, () -> 70_010L, 5_000L, 30_000L);
            registerReady(registry, "peer-1", observer);
            assertTrue(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", 70_000L));

            registry.completeAll();

            assertTrue(observer.completed);
            assertEquals(0, registry.connectedCount());
            assertEquals(List.of("SESSION_CLOSE:AGENT_KILL_ACK_SERVER_SHUTDOWN"),
                    audits.stream().map(RemoteBridgeMessages.AuditEvent::eventType).toList());
            assertEquals(ControlStreamRegistry.OperatorKillAckResult.REFUSED_NO_PENDING,
                    registry.acceptOperatorKillAcknowledgement(peer("peer-1"), killAck("sess-1", 70_005L), 70_010L));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void serverShutdownClosesEveryControlHandleBeforePendingAuditCanBlock() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch releaseAudit = new CountDownLatch(1);
        try {
            CountDownLatch auditEntered = new CountDownLatch(1);
            ControlStreamRegistry registry = new ControlStreamRegistry(scheduler, event -> {
                auditEntered.countDown();
                await(releaseAudit);
            }, () -> 75_010L, 5_000L, 30_000L);
            CapturingObserver pendingObserver = new CapturingObserver();
            CapturingObserver ordinaryObserver = new CapturingObserver();
            registerReady(registry, "peer-1", pendingObserver);
            registerReady(registry, "peer-2", ordinaryObserver);
            assertTrue(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", 75_000L));

            Thread shutdown = Thread.ofPlatform().start(registry::completeAll);
            assertTrue(auditEntered.await(2, TimeUnit.SECONDS));

            assertTrue(pendingObserver.completed);
            assertTrue(ordinaryObserver.completed,
                    "a blocked pending audit cannot keep an unrelated CONTROL stream open");
            assertEquals(0, registry.connectedCount());
            assertTrue(shutdown.isAlive(), "the test must observe the recorder blocking after transport cleanup");
            assertFalse(registry.sendConsentPrompt("peer-2", prompt("sess-late"), 75_011L),
                    "server shutdown must fence every new application-control send");
            assertEquals(0, ordinaryObserver.sent.size());

            CapturingObserver lateObserver = new CapturingObserver();
            registry.register(peer("peer-late"), new ControlStreamHandle(lateObserver));
            assertTrue(lateObserver.completed,
                    "an already-accepted inbound call cannot publish a CONTROL generation after the shutdown fence");
            assertEquals(0, registry.connectedCount());

            releaseAudit.countDown();
            shutdown.join(2_000L);
            assertFalse(shutdown.isAlive());
        } finally {
            releaseAudit.countDown();
            scheduler.shutdownNow();
        }
    }

    @Test
    void serverShutdownClosesEveryTransportBeforeBrokerCleanupCanBlock() throws Exception {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity firstPeer = peer("peer-1");
        PeerIdentity secondPeer = peer("peer-2");
        CapturingObserver firstObserver = new CapturingObserver();
        CapturingObserver secondObserver = new CapturingObserver();
        ControlStreamHandle first = registerReady(registry, "peer-1", firstObserver);
        ControlStreamHandle second = registerReady(registry, "peer-2", secondObserver);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        AtomicInteger firstCleanups = new AtomicInteger();
        AtomicInteger secondCleanups = new AtomicInteger();
        first.attachOnClose(() -> registry.unregister(firstPeer, first, () -> {
            firstCleanups.incrementAndGet();
            cleanupEntered.countDown();
            await(releaseCleanup);
        }));
        second.attachOnClose(() -> registry.unregister(secondPeer, second, secondCleanups::incrementAndGet));

        Thread shutdown = Thread.ofPlatform().start(registry::completeAll);
        assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS));

        assertTrue(firstObserver.completed);
        assertTrue(secondObserver.completed,
                "an earlier broker cleanup cannot keep another CONTROL transport open");
        assertEquals(0, registry.connectedCount());

        releaseCleanup.countDown();
        shutdown.join(2_000L);
        assertFalse(shutdown.isAlive());
        assertEquals(1, firstCleanups.get());
        assertEquals(1, secondCleanups.get());
    }

    @Test
    void shutdownFenceDropsPreparedConsentBeforeItsGenerationCommit() throws Exception {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        ControlStreamHandle handle = registerReady(registry, "peer-1", observer);
        CountDownLatch preparationEntered = new CountDownLatch(1);
        CountDownLatch releasePreparation = new CountDownLatch(1);
        AtomicBoolean committed = new AtomicBoolean();
        AtomicReference<Boolean> dispatched = new AtomicReference<>();

        Thread consent = Thread.ofPlatform().start(() -> dispatched.set(
                registry.dispatchPreparedConsentIfCurrentHelloObserved(peer("peer-1"), handle, () -> {
                    preparationEntered.countDown();
                    await(releasePreparation);
                    return () -> committed.set(true);
                })));
        assertTrue(preparationEntered.await(2, TimeUnit.SECONDS));

        registry.completeAll();
        releasePreparation.countDown();
        consent.join(2_000L);

        assertFalse(consent.isAlive());
        assertEquals(false, dispatched.get());
        assertFalse(committed.get(), "shutdown must fence a prepared mutation before its generation commit");
        assertTrue(observer.completed);
        assertEquals(0, registry.connectedCount());
    }

    @Test
    void unavailableAckSchedulerFallsBackToImmediateFailClosedKillWithDurableReason() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.shutdownNow();
        ConcurrentLinkedQueue<RemoteBridgeMessages.AuditEvent> audits = new ConcurrentLinkedQueue<>();
        CapturingObserver observer = new CapturingObserver();
        ControlStreamRegistry registry = new ControlStreamRegistry(
                scheduler, audits::add, () -> 80_000L, 5_000L, 30_000L);
        registerReady(registry, "peer-1", observer);

        assertTrue(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", 80_000L));

        assertTrue(observer.completed, "capture termination remains fail-closed when ACK scheduling is unavailable");
        assertFalse(registry.isConnected("peer-1"));
        assertEquals(List.of("SESSION_CLOSE:AGENT_KILL_ACK_SCHEDULER_UNAVAILABLE"),
                audits.stream().map(RemoteBridgeMessages.AuditEvent::eventType).toList());
    }

    @Test
    void staleInboundHeartbeatMakesApplicationControlUnavailableUntilFreshHeartbeatArrives() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicLong now = new AtomicLong(100_000L);
            ControlStreamRegistry registry = new ControlStreamRegistry(
                    scheduler, event -> { }, now::get, 5_000L, 30_000L, 3_000L);
            PeerIdentity identity = peer("peer-1");
            CapturingObserver observer = new CapturingObserver();
            ControlStreamHandle handle = new ControlStreamHandle(observer);
            registry.register(identity, handle);
            assertTrue(registry.absorbAgentHello(identity, handle, () -> { }));
            assertTrue(registry.sendConsentPrompt("peer-1", prompt("sess-fresh"), now.get()));

            now.addAndGet(3_001L);
            assertFalse(registry.isConnected("peer-1"));
            assertTrue(registry.connectedPeer("peer-1").isEmpty());
            assertFalse(registry.sendConsentPrompt("peer-1", prompt("sess-stale"), now.get()),
                    "a half-open StreamObserver must not count as prompt delivery");

            assertTrue(registry.dispatchPreparedHeartbeatIfCurrentHelloObserved(
                    identity, handle, () -> () -> { }));
            assertTrue(registry.isConnected("peer-1"));
            assertTrue(registry.sendConsentPrompt("peer-1", prompt("sess-recovered"), now.get()));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void concurrentAckAndReconnectProduceExactlyOneOldHandleOutcomeAndPreserveSuccessor() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            for (int iteration = 0; iteration < 100; iteration++) {
                long base = 90_000L + iteration * 100L;
                AtomicLong now = new AtomicLong(base + 10L);
                ConcurrentLinkedQueue<RemoteBridgeMessages.AuditEvent> audits = new ConcurrentLinkedQueue<>();
                ControlStreamRegistry registry = new ControlStreamRegistry(
                        scheduler, audits::add, now::get, 5_000L, 30_000L);
                CapturingObserver oldObserver = new CapturingObserver();
                registerReady(registry, "peer-1", oldObserver);
                assertTrue(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", base));

                CapturingObserver successorObserver = new CapturingObserver();
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                AtomicReference<ControlStreamRegistry.OperatorKillAckResult> ackResult = new AtomicReference<>();
                Thread ack = Thread.ofPlatform().start(() -> {
                    ready.countDown();
                    await(start);
                    ackResult.set(registry.acceptOperatorKillAcknowledgement(
                            peer("peer-1"), killAck("sess-1", base + 11L), base + 12L));
                });
                Thread reconnect = Thread.ofPlatform().start(() -> {
                    ready.countDown();
                    await(start);
                    registerReady(registry, "peer-1", successorObserver);
                });
                assertTrue(ready.await(2, TimeUnit.SECONDS));
                start.countDown();
                ack.join(2_000L);
                reconnect.join(2_000L);

                assertFalse(ack.isAlive());
                assertFalse(reconnect.isAlive());
                assertTrue(oldObserver.completed);
                assertFalse(successorObserver.completed,
                        "iteration " + iteration + ": old ACK cleanup must never close the successor");
                assertTrue(registry.isConnected("peer-1"));
                assertEquals(1, audits.size(),
                        "iteration " + iteration + ": old handle gets exactly one durable terminal outcome");
                String outcome = audits.element().eventType();
                assertTrue(outcome.equals("SESSION_CLOSE:AGENT_KILL_APPLIED")
                                || outcome.equals("SESSION_CLOSE:AGENT_KILL_ACK_STREAM_REPLACED"),
                        "iteration " + iteration + ": unexpected outcome " + outcome);
                if (outcome.equals("SESSION_CLOSE:AGENT_KILL_APPLIED")) {
                    assertEquals(ControlStreamRegistry.OperatorKillAckResult.ACKNOWLEDGED, ackResult.get());
                } else {
                    assertTrue(ackResult.get() == ControlStreamRegistry.OperatorKillAckResult.REFUSED_NO_PENDING
                                    || ackResult.get()
                                    == ControlStreamRegistry.OperatorKillAckResult.REFUSED_HANDLE_MISMATCH,
                            "iteration " + iteration + ": replacement winner must refuse old ACK");
                }
                registry.completeAll();
            }
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void concurrentArmsForTheSamePeerCollapseToOneNonExtendedProbe() throws Exception {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        registry.register(peer("peer-1"), new ControlStreamHandle(new CapturingObserver()));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<ControlStreamRegistry.HeartbeatSuppressionTicket> outcomes =
                new ConcurrentLinkedQueue<>();
        Thread first = Thread.ofPlatform().start(() -> {
            ready.countDown();
            await(start);
            registry.suppressHeartbeats("peer-1", "probe-1", 1_000L, 2_000L).ifPresent(outcomes::add);
        });
        Thread second = Thread.ofPlatform().start(() -> {
            ready.countDown();
            await(start);
            registry.suppressHeartbeats("peer-1", "probe-2", 1_000L, 9_000L).ifPresent(outcomes::add);
        });

        assertTrue(ready.await(2, TimeUnit.SECONDS));
        start.countDown();
        first.join(2_000L);
        second.join(2_000L);

        assertEquals(2, outcomes.size());
        List<ControlStreamRegistry.HeartbeatSuppressionTicket> tickets = List.copyOf(outcomes);
        assertEquals(tickets.get(0).probeId(), tickets.get(1).probeId());
        assertEquals(tickets.get(0).suppressedUntilEpochMillis(), tickets.get(1).suppressedUntilEpochMillis());
        assertEquals(1, tickets.stream().filter(ControlStreamRegistry.HeartbeatSuppressionTicket::newlyArmed).count());
    }

    @Test
    void heartbeatFaultObservationLedgerHasAHardCapacityAndOverflowDoesNotSuppress() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        for (int i = 0; i < 1_024; i++) {
            String peerKey = "peer-" + i;
            registry.register(peer(peerKey), new ControlStreamHandle(new CapturingObserver()));
            assertTrue(registry.suppressHeartbeats(peerKey, "probe-" + i,
                    1_000L, 2_000L).isPresent());
        }
        ControlStreamHandle overflowHandle = new ControlStreamHandle(new CapturingObserver());
        registry.register(peer("peer-overflow"), overflowHandle);

        assertTrue(registry.suppressHeartbeats("peer-overflow", "probe-overflow",
                1_000L, 2_000L).isEmpty());
        Envelope heartbeat = Envelope.newBuilder().setChannelType(ChannelType.CONTROL)
                .setHeartbeat(Heartbeat.newBuilder().setHeartbeatIntervalMillis(1_000L)).build();
        assertTrue(overflowHandle.sendHeartbeat(heartbeat, 1_500L),
                "capacity refusal must roll back the just-armed suppression");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
