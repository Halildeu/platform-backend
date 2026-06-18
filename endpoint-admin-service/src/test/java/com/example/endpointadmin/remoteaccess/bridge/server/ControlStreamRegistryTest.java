package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgePermitSigner;
import com.example.endpointadmin.remoteaccess.bridge.contract.CanonicalCommand;
import com.example.endpointadmin.remoteaccess.bridge.contract.OperationPermit;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.proto.ChannelType;
import com.example.endpointadmin.remoteaccess.bridge.proto.Envelope;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void killPeerClearsTheConnectedPeer() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity p = peer("peer-1");
        registry.register(p, new ControlStreamHandle(new CapturingObserver()));

        assertTrue(registry.killPeer("peer-1", "sess-1", "duress", 1L));
        assertTrue(registry.connectedPeer("peer-1").isEmpty());
    }
}
