package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.proto.ChannelType;
import com.example.endpointadmin.remoteaccess.bridge.proto.Envelope;
import com.example.endpointadmin.remoteaccess.bridge.proto.Heartbeat;
import com.example.endpointadmin.remoteaccess.bridge.proto.Kill;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void closeActionRunsOutsideTheStreamMonitor() {
        ControlStreamHandle handle = new ControlStreamHandle(quietObserver());
        AtomicBoolean monitorHeld = new AtomicBoolean(true);
        handle.attachOnClose(() -> monitorHeld.set(Thread.holdsLock(handle)));

        handle.close();

        assertFalse(monitorHeld.get(), "broker cleanup must never run under the transport serialization lock");
    }

    @Test
    void observerReentrantCloseDefersCleanupUntilTheOutermostSendReleasesTheMonitor() {
        ControlStreamHandle[] holder = new ControlStreamHandle[1];
        AtomicBoolean monitorHeld = new AtomicBoolean(true);
        AtomicInteger closes = new AtomicInteger();
        StreamObserver<Envelope> reentrantObserver = new StreamObserver<>() {
            @Override
            public void onNext(Envelope envelope) {
                holder[0].close();
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
            }
        };
        ControlStreamHandle handle = new ControlStreamHandle(reentrantObserver);
        holder[0] = handle;
        handle.attachOnClose(() -> {
            monitorHeld.set(Thread.holdsLock(handle));
            closes.incrementAndGet();
        });

        assertTrue(handle.send(HEARTBEAT));

        assertTrue(handle.isClosed());
        assertFalse(monitorHeld.get());
        assertEquals(1, closes.get());
    }

    @Test
    void closeActionMayReenterCloseWithoutRunningUnderTheMonitor() {
        ControlStreamHandle handle = new ControlStreamHandle(quietObserver());
        AtomicBoolean monitorHeld = new AtomicBoolean();
        AtomicInteger actions = new AtomicInteger();
        handle.attachOnClose(() -> {
            monitorHeld.set(Thread.holdsLock(handle));
            actions.incrementAndGet();
            handle.close(); // re-entrant and idempotent
        });

        handle.close();

        assertFalse(monitorHeld.get());
        assertEquals(1, actions.get());
    }

    @Test
    void aSecondOnCloseAttachmentCannotReplaceRegistryCleanup() {
        ControlStreamHandle handle = new ControlStreamHandle(quietObserver());
        handle.attachOnClose(() -> { });

        assertThrows(IllegalStateException.class, () -> handle.attachOnClose(() -> { }));
        handle.close();
        assertThrows(IllegalStateException.class, () -> handle.attachOnClose(() -> { }));
    }

    @Test
    void sendAndCloseStillRunsCleanupWhenTheObserverWriteThrows() {
        ControlStreamHandle handle = new ControlStreamHandle(throwingObserver());
        AtomicInteger closes = new AtomicInteger();
        handle.attachOnClose(closes::incrementAndGet);

        assertFalse(handle.sendAndClose(HEARTBEAT));

        assertTrue(handle.isClosed());
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

    @Test
    void heartbeatSuppressionDoesNotBlockGeneralControlAndAutomaticallyExpires() {
        AtomicInteger writes = new AtomicInteger();
        StreamObserver<Envelope> observer = new StreamObserver<>() {
            @Override public void onNext(Envelope envelope) { writes.incrementAndGet(); }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        };
        ControlStreamHandle handle = new ControlStreamHandle(observer);

        ControlStreamHandle.HeartbeatSuppression armed =
                handle.suppressHeartbeats("probe-1", 1_000L, 2_000L);

        assertTrue(armed.newlyArmed());
        assertFalse(handle.sendHeartbeat(HEARTBEAT, 1_500L), "heartbeat is suppressed inside the window");
        assertTrue(handle.send(HEARTBEAT), "general CONTROL writes remain deliverable");
        assertTrue(handle.sendHeartbeat(HEARTBEAT, 2_000L), "deadline automatically restores heartbeats");
        assertEquals(2, writes.get());
    }

    @Test
    void repeatedActiveSuppressionIsIdempotentAndCannotExtendTheDeadline() {
        ControlStreamHandle handle = new ControlStreamHandle(quietObserver());

        ControlStreamHandle.HeartbeatSuppression first =
                handle.suppressHeartbeats("probe-1", 1_000L, 2_000L);
        ControlStreamHandle.HeartbeatSuppression repeated =
                handle.suppressHeartbeats("probe-2", 1_500L, 9_000L);

        assertTrue(first.newlyArmed());
        assertFalse(repeated.newlyArmed());
        assertEquals("probe-1", repeated.probeId());
        assertEquals(2_000L, repeated.untilEpochMillis());
    }

    @Test
    void killDeliveryAndTerminalCloseRemainAvailableDuringHeartbeatSuppression() {
        AtomicInteger kills = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        StreamObserver<Envelope> observer = new StreamObserver<>() {
            @Override public void onNext(Envelope envelope) {
                if (envelope.getPayloadCase() == Envelope.PayloadCase.KILL) {
                    kills.incrementAndGet();
                }
            }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { completions.incrementAndGet(); }
        };
        ControlStreamHandle handle = new ControlStreamHandle(observer);
        handle.suppressHeartbeats("probe-1", 1_000L, 2_000L);
        Envelope kill = Envelope.newBuilder().setChannelType(ChannelType.CONTROL).setSessionId("sess-1")
                .setKill(Kill.newBuilder().setSessionId("sess-1").setKillReason("operator-close")
                        .setIssuedAtEpochMillis(1_500L))
                .build();

        assertTrue(handle.sendAndClose(kill));
        assertEquals(1, kills.get());
        assertEquals(1, completions.get());
        assertTrue(handle.isClosed());
    }

    @Test
    void scheduledHeartbeatPathRejectsAnyOtherControlPayload() {
        ControlStreamHandle handle = new ControlStreamHandle(quietObserver());
        assertThrows(IllegalArgumentException.class,
                () -> handle.sendHeartbeat(Envelope.getDefaultInstance(), 1_000L));
    }
}
