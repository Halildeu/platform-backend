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

    private static PeerIdentity peer(String key) {
        return new PeerIdentity(key, Optional.empty(), List.<X509Certificate>of());
    }

    private static PeerIdentity peerByAdComputer(String key, UUID objectGuid) {
        return new PeerIdentity(key, Optional.empty(), Optional.of(objectGuid.toString()),
                List.<X509Certificate>of());
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
    void sendsOperationPermitOnControlToTheLivePeer() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        CapturingObserver observer = new CapturingObserver();
        registry.register(peer("peer-1"), new ControlStreamHandle(observer));

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
        registry.register(peer("peer-1"), new ControlStreamHandle(observer));

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
        registry.register(peer("peer-1"), new ControlStreamHandle(observer));

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
        registry.register(peer("peer-1"), new ControlStreamHandle(observer));

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
        registry.register(p, new ControlStreamHandle(new CapturingObserver()));

        assertEquals(p, registry.connectedPeer("peer-1").orElseThrow());
        assertTrue(registry.connectedPeer("ghost").isEmpty());
        assertTrue(registry.connectedPeer(null).isEmpty());
    }

    @Test
    void connectedPeerByAdComputerIdFindsOnlyACurrentRegisteredSanBoundPeer() {
        UUID objectGuid = UUID.fromString("44444444-4444-4444-4444-444444444444");
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity p = peerByAdComputer("peer-1", objectGuid);
        ControlStreamHandle handle = new ControlStreamHandle(new CapturingObserver());
        registry.register(p, handle);

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

        registry.unregister(p, first); // stale handle
        assertTrue(registry.connectedPeer("peer-1").isPresent(), "the successor stream must survive a stale unregister");
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
            ControlStreamHandle handle = new ControlStreamHandle(observer);
            registry.register(peer("peer-1"), handle);
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
            registry.register(peer("peer-1"), new ControlStreamHandle(observer));
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
            registry.register(peer("peer-1"), new ControlStreamHandle(observer));
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
            registry.register(peer("peer-1"), new ControlStreamHandle(observer));
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
            registry.register(peer("peer-1"), new ControlStreamHandle(observer));
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
            registry.register(peer("peer-1"), new ControlStreamHandle(observer));

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
    void reconnectFailsPendingAckButNeverClosesTheSuccessor() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicLong now = new AtomicLong(30_010L);
            ConcurrentLinkedQueue<RemoteBridgeMessages.AuditEvent> audits = new ConcurrentLinkedQueue<>();
            ControlStreamRegistry registry = new ControlStreamRegistry(
                    scheduler, audits::add, now::get, 5_000L, 30_000L);
            CapturingObserver oldObserver = new CapturingObserver();
            registry.register(peer("peer-1"), new ControlStreamHandle(oldObserver));
            assertTrue(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", 30_000L));

            CapturingObserver successorObserver = new CapturingObserver();
            registry.register(peer("peer-1"), new ControlStreamHandle(successorObserver));

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
    void durableAckAuditFailureNeverLeavesTheTransportOrPendingClaimOpen() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            ControlStreamRegistry registry = new ControlStreamRegistry(scheduler, event -> {
                throw new IllegalStateException("durable recorder unavailable");
            }, () -> 40_010L, 5_000L, 30_000L);
            CapturingObserver observer = new CapturingObserver();
            registry.register(peer("peer-1"), new ControlStreamHandle(observer));
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
            registry.register(peer("peer-1"), new ControlStreamHandle(deadObserver));

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
            registry.register(peer("peer-1"), new ControlStreamHandle(observer));

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
            registry.register(peer("peer-1"), new ControlStreamHandle(observer));
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
    void unavailableAckSchedulerFallsBackToImmediateFailClosedKillWithDurableReason() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.shutdownNow();
        ConcurrentLinkedQueue<RemoteBridgeMessages.AuditEvent> audits = new ConcurrentLinkedQueue<>();
        CapturingObserver observer = new CapturingObserver();
        ControlStreamRegistry registry = new ControlStreamRegistry(
                scheduler, audits::add, () -> 80_000L, 5_000L, 30_000L);
        registry.register(peer("peer-1"), new ControlStreamHandle(observer));

        assertTrue(registry.killPeerAwaitingOperatorAck("peer-1", "sess-1", 80_000L));

        assertTrue(observer.completed, "capture termination remains fail-closed when ACK scheduling is unavailable");
        assertFalse(registry.isConnected("peer-1"));
        assertEquals(List.of("SESSION_CLOSE:AGENT_KILL_ACK_SCHEDULER_UNAVAILABLE"),
                audits.stream().map(RemoteBridgeMessages.AuditEvent::eventType).toList());
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
                registry.register(peer("peer-1"), new ControlStreamHandle(oldObserver));
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
                    registry.register(peer("peer-1"), new ControlStreamHandle(successorObserver));
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
