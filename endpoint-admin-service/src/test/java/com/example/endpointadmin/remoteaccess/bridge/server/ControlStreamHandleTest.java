package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.proto.ChannelType;
import com.example.endpointadmin.remoteaccess.bridge.proto.Envelope;
import com.example.endpointadmin.remoteaccess.bridge.proto.Heartbeat;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 T-2b follow-up (Codex 019eb9fb non-blocking note) — the handle's run-once on-close action vacates
 * the registry slot on EVERY termination path, including a heartbeat write dying on a broken stream (the one
 * path with no inbound observer to do the unregistering until gRPC delivers the cancellation).
 */
class ControlStreamHandleTest {

    private static final Envelope HEARTBEAT = Envelope.newBuilder()
            .setChannelType(ChannelType.CONTROL)
            .setHeartbeat(Heartbeat.newBuilder().setHeartbeatIntervalMillis(50))
            .build();

    /** A stream whose writes always die (broken transport). */
    private static StreamObserver<Envelope> throwingObserver() {
        return new StreamObserver<>() {
            @Override
            public void onNext(Envelope envelope) {
                throw new IllegalStateException("stream is dead");
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
            }
        };
    }

    private static StreamObserver<Envelope> quietObserver() {
        return new StreamObserver<>() {
            @Override
            public void onNext(Envelope envelope) {
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
            }
        };
    }

    @Test
    void aFailedWriteSelfClosesAndRunsTheOnCloseActionExactlyOnce() {
        ControlStreamHandle handle = new ControlStreamHandle(throwingObserver());
        AtomicInteger closes = new AtomicInteger();
        handle.attachOnClose(closes::incrementAndGet);

        assertFalse(handle.send(HEARTBEAT)); // dies → self-close → on-close ran
        assertTrue(handle.isClosed());
        assertEquals(1, closes.get());

        handle.close(); // idempotent — never a second run
        handle.sendAndClose(HEARTBEAT);
        assertEquals(1, closes.get());
    }

    @Test
    void everyTerminationPathRunsTheOnCloseActionOnce() {
        ControlStreamHandle closed = new ControlStreamHandle(quietObserver());
        AtomicInteger viaClose = new AtomicInteger();
        closed.attachOnClose(viaClose::incrementAndGet);
        closed.close();
        assertEquals(1, viaClose.get());

        ControlStreamHandle killed = new ControlStreamHandle(quietObserver());
        AtomicInteger viaKill = new AtomicInteger();
        killed.attachOnClose(viaKill::incrementAndGet);
        killed.sendAndClose(HEARTBEAT);
        assertEquals(1, viaKill.get());
    }

    @Test
    void attachingToAnAlreadyClosedHandleRunsImmediately() {
        ControlStreamHandle handle = new ControlStreamHandle(quietObserver());
        handle.close();
        AtomicInteger closes = new AtomicInteger();
        handle.attachOnClose(closes::incrementAndGet);
        assertEquals(1, closes.get());
    }

    @Test
    void aDyingHeartbeatWriteVacatesTheRegistrySlot() {
        // the exact scenario from the Codex note: no inbound event has arrived yet, the scheduler's write
        // dies on a broken stream — the slot must NOT stay claimed until gRPC cancellation
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity peer = new PeerIdentity("peer-fp-x", Optional.empty(), List.of());
        ControlStreamHandle handle = new ControlStreamHandle(throwingObserver());
        registry.register(peer, handle);
        handle.attachOnClose(() -> registry.unregister(peer, handle));
        assertTrue(registry.isConnected("peer-fp-x"));

        assertFalse(handle.send(HEARTBEAT)); // the heartbeat task's write path

        assertFalse(registry.isConnected("peer-fp-x"));
        assertEquals(0, registry.connectedCount());
    }

    @Test
    void aReplacedHandlesOnCloseDoesNotUnregisterItsSuccessor() {
        ControlStreamRegistry registry = new ControlStreamRegistry();
        PeerIdentity peer = new PeerIdentity("peer-fp-x", Optional.empty(), List.of());

        ControlStreamHandle first = new ControlStreamHandle(quietObserver());
        registry.register(peer, first);
        first.attachOnClose(() -> registry.unregister(peer, first));

        ControlStreamHandle second = new ControlStreamHandle(quietObserver());
        registry.register(peer, second); // closes first → first's on-close runs → remove(key, first) no-ops
        second.attachOnClose(() -> registry.unregister(peer, second));

        assertTrue(first.isClosed());
        assertTrue(registry.isConnected("peer-fp-x"));
        assertEquals(1, registry.connectedCount());
    }
}
