package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.proto.Envelope;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.ScheduledFuture;

/**
 * Faz 22.6 T-2b (Codex 019eb9fb P2) — the serialized write path for ONE CONTROL stream. A gRPC
 * {@link StreamObserver} is not a thread-safe sink, yet a CONTROL stream is written by the inbound handler
 * (error frames), the heartbeat scheduler, the registry replace-on-reconnect, and {@code killPeer} — so every
 * outbound write/termination goes through this handle's monitor, and closing the handle also cancels ITS
 * heartbeat task (a replaced stream's timer must not keep writing to a completed observer until shutdown).
 *
 * <p>Idempotent and terminal: after {@link #close()} (or a failed write, which self-closes) every further
 * send is a refused no-op. An attached on-close action (the registry unregistration) runs EXACTLY once on
 * whichever path terminates the handle first — so a heartbeat write that dies on a broken stream cannot
 * leave a stale registry slot behind until gRPC delivers the inbound cancellation (Codex 019eb9fb T-2b
 * follow-up note).
 */
final class ControlStreamHandle {

    record HeartbeatSuppression(String probeId, long untilEpochMillis, boolean newlyArmed) {
    }

    private final StreamObserver<Envelope> outbound;
    private ScheduledFuture<?> heartbeat;
    private Runnable onClose;
    private Runnable pendingCloseAction;
    private boolean closeActionAttached;
    private String heartbeatSuppressionProbeId;
    private long heartbeatSuppressedUntilEpochMillis;
    private boolean heartbeatFaultTerminalClaimed;
    private boolean operatorKillPending;
    private volatile boolean closed;

    ControlStreamHandle(StreamObserver<Envelope> outbound) {
        this.outbound = outbound;
    }

    /**
     * Attach the one run-once close action (registry unregistration); runs immediately when already closed.
     * A second attachment at any lifecycle point is a programming error: silently replacing or duplicating the
     * registry cleanup action could leave a dead stream registered or emit duplicate terminal policy events.
     */
    void attachOnClose(Runnable action) {
        if (action == null) {
            return;
        }
        synchronized (this) {
            if (closeActionAttached) {
                throw new IllegalStateException("on-close action already attached");
            }
            closeActionAttached = true;
            if (closed) {
                pendingCloseAction = action;
            } else {
                this.onClose = action;
            }
        }
        drainCloseAction();
    }

    /** Attach this stream's heartbeat task; cancelled immediately when the handle is already closed. */
    synchronized void attachHeartbeat(ScheduledFuture<?> future) {
        if (future == null) {
            return;
        }
        if (closed) {
            future.cancel(false);
            return;
        }
        this.heartbeat = future;
    }

    /** Serialized write; false when the handle is closed or the stream is dead (which self-closes). */
    boolean send(Envelope envelope) {
        return sendInternal(envelope, true);
    }

    /** Application/control authority must not reuse a handle quarantined for an operator-close ACK. */
    boolean sendApplicationControl(Envelope envelope) {
        return sendInternal(envelope, false);
    }

    private boolean sendInternal(Envelope envelope, boolean allowedDuringOperatorKill) {
        boolean delivered;
        synchronized (this) {
            if (closed || (!allowedDuringOperatorKill && operatorKillPending)) {
                return false;
            }
            try {
                outbound.onNext(envelope);
                delivered = true;
            } catch (RuntimeException e) {
                closeLocked();
                delivered = false;
            }
        }
        drainCloseAction();
        return delivered;
    }

    /** One-way quarantine: this exact handle can carry only heartbeat/KILL/ACK teardown until it closes. */
    synchronized boolean quarantineForOperatorKill() {
        if (closed || operatorKillPending) {
            return false;
        }
        operatorKillPending = true;
        return true;
    }

    synchronized boolean isApplicationControlAvailable() {
        return !closed && !operatorKillPending;
    }

    /**
     * Heartbeat-only write path used by the scheduler. A bounded acceptance probe may suppress these frames
     * without suppressing KILL, permits, consent prompts, or any other CONTROL payload sent through
     * {@link #send(Envelope)}. Suppression automatically expires at the broker-owned deadline.
     */
    boolean sendHeartbeat(Envelope envelope, long nowEpochMillis) {
        if (envelope == null || envelope.getPayloadCase() != Envelope.PayloadCase.HEARTBEAT) {
            throw new IllegalArgumentException("scheduled heartbeat path requires a heartbeat envelope");
        }
        boolean delivered;
        synchronized (this) {
            if (closed || nowEpochMillis < heartbeatSuppressedUntilEpochMillis) {
                return false;
            }
            try {
                outbound.onNext(envelope);
                delivered = true;
            } catch (RuntimeException e) {
                closeLocked();
                delivered = false;
            }
        }
        drainCloseAction();
        return delivered;
    }

    /** Arm one bounded heartbeat-loss probe; a repeated call while active is idempotent and never extends it. */
    synchronized HeartbeatSuppression suppressHeartbeats(String probeId,
                                                          long nowEpochMillis,
                                                          long untilEpochMillis) {
        if (closed || probeId == null || probeId.isBlank() || untilEpochMillis <= nowEpochMillis) {
            return null;
        }
        if (nowEpochMillis < heartbeatSuppressedUntilEpochMillis
                && heartbeatSuppressionProbeId != null) {
            return new HeartbeatSuppression(heartbeatSuppressionProbeId,
                    heartbeatSuppressedUntilEpochMillis, false);
        }
        heartbeatSuppressionProbeId = probeId;
        heartbeatSuppressedUntilEpochMillis = untilEpochMillis;
        heartbeatFaultTerminalClaimed = false;
        return new HeartbeatSuppression(probeId, untilEpochMillis, true);
    }

    synchronized String heartbeatSuppressionProbeId() {
        return heartbeatSuppressionProbeId;
    }

    synchronized String cancelHeartbeatSuppression() {
        String probeId = heartbeatSuppressionProbeId;
        heartbeatSuppressionProbeId = null;
        heartbeatSuppressedUntilEpochMillis = 0L;
        heartbeatFaultTerminalClaimed = false;
        return probeId;
    }

    /** Exactly one stale-reconnect callback may claim terminal cleanup for an armed heartbeat probe. */
    synchronized boolean claimHeartbeatFaultTerminalCleanup() {
        if (heartbeatSuppressionProbeId == null || heartbeatFaultTerminalClaimed) {
            return false;
        }
        heartbeatFaultTerminalClaimed = true;
        return true;
    }

    /** Send (best-effort) then terminate — the KILL path and the error path share this. */
    boolean sendAndClose(Envelope envelope) {
        boolean delivered = false;
        synchronized (this) {
            if (closed) {
                return false;
            }
            try {
                outbound.onNext(envelope);
                delivered = true;
            } catch (RuntimeException ignored) {
                // the stream is already dead — closing is the outcome that matters
            }
            closeLocked();
        }
        drainCloseAction();
        return delivered;
    }

    /** Terminate the stream: cancel the heartbeat, complete the observer. Idempotent. */
    void close() {
        synchronized (this) {
            closeLocked();
        }
        drainCloseAction();
    }

    boolean isClosed() {
        return closed;
    }

    /** Mark terminal under this monitor; callbacks are queued for the outermost caller to drain after release. */
    private void closeLocked() {
        if (closed) {
            return;
        }
        closed = true;
        if (heartbeat != null) {
            heartbeat.cancel(false);
            heartbeat = null;
        }
        try {
            outbound.onCompleted();
        } catch (RuntimeException ignored) {
            // already terminated
        }
        if (onClose != null) {
            pendingCloseAction = onClose;
        }
        onClose = null;
    }

    private void drainCloseAction() {
        // An observer is allowed to synchronously re-enter close() from onNext/onCompleted. Java monitors are
        // reentrant, so leaving close()'s nested synchronized block does not mean the outer send released the
        // lock. Defer until the outermost transport call exits; this preserves serialized observer writes while
        // guaranteeing policy cleanup never runs under the transport monitor.
        if (Thread.holdsLock(this)) {
            return;
        }
        Runnable action;
        synchronized (this) {
            action = pendingCloseAction;
            pendingCloseAction = null;
        }
        if (action != null) {
            // Registry removal may synchronously trigger broker session cleanup and durable audit. Never execute
            // that work under the stream monitor: doing so would couple transport serialization to policy I/O.
            action.run();
        }
    }
}
