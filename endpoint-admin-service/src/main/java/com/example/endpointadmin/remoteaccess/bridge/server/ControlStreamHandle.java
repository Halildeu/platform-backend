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

    private final StreamObserver<Envelope> outbound;
    private ScheduledFuture<?> heartbeat;
    private Runnable onClose;
    private boolean closed;

    ControlStreamHandle(StreamObserver<Envelope> outbound) {
        this.outbound = outbound;
    }

    /** Attach the run-once close action (registry unregistration); runs immediately when already closed. */
    synchronized void attachOnClose(Runnable action) {
        if (action == null) {
            return;
        }
        if (closed) {
            action.run();
            return;
        }
        this.onClose = action;
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
    synchronized boolean send(Envelope envelope) {
        if (closed) {
            return false;
        }
        try {
            outbound.onNext(envelope);
            return true;
        } catch (RuntimeException e) {
            closeLocked();
            return false;
        }
    }

    /** Send (best-effort) then terminate — the KILL path and the error path share this. */
    synchronized boolean sendAndClose(Envelope envelope) {
        if (closed) {
            return false;
        }
        boolean delivered = false;
        try {
            outbound.onNext(envelope);
            delivered = true;
        } catch (RuntimeException ignored) {
            // the stream is already dead — closing is the outcome that matters
        }
        closeLocked();
        return delivered;
    }

    /** Terminate the stream: cancel the heartbeat, complete the observer. Idempotent. */
    synchronized void close() {
        if (!closed) {
            closeLocked();
        }
    }

    synchronized boolean isClosed() {
        return closed;
    }

    private void closeLocked() {
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
            Runnable action = onClose;
            onClose = null;
            // ConcurrentHashMap remove(key, value) — lock-free, reentrant-safe from this monitor
            action.run();
        }
    }
}
