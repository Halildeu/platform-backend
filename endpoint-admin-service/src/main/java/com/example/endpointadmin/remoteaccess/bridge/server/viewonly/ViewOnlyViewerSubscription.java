package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import java.util.Objects;
import java.util.Optional;

/**
 * Faz 22.6 #1580 (Codex 019f078a; race fix 019f0e78) — one operator viewer's handle on a VIEW_ONLY session's
 * live screen frames.
 *
 * <p><b>Latest-wins, single slot, never blocks.</b> The DATA-plane handler thread {@link #offer(ViewOnlyFrame)}s
 * the newest frame; if the viewer has not consumed the previous one it is overwritten (the OLD frame is dropped,
 * not persisted). A slow or stalled viewer can never apply backpressure to the agent's DATA stream and can never
 * grow an unbounded buffer.
 *
 * <p><b>offer / poll / close are mutually atomic</b> (single monitor): once {@link #close()} runs, no later
 * {@code offer} can re-populate the slot, so a screen frame can never be retained past termination (the privacy
 * cleanup invariant — a prior lock-free version had an offer/close interleaving that could re-store a frame after
 * close). The optional {@code frameAvailable} wake hook is invoked OUTSIDE the lock (so a viewer-side listener
 * can never deadlock the DATA thread) and only when the offer was accepted; any throw from it is swallowed.
 */
public final class ViewOnlyViewerSubscription {

    private final String sessionId;
    private final String viewerId;
    private final Runnable frameAvailable;
    private final Object lock = new Object();
    private ViewOnlyFrame latest;   // guarded by lock
    private boolean closed;         // guarded by lock

    ViewOnlyViewerSubscription(String sessionId, String viewerId, Runnable frameAvailable) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.viewerId = Objects.requireNonNull(viewerId, "viewerId");
        this.frameAvailable = frameAvailable;
    }

    public String sessionId() {
        return sessionId;
    }

    public String viewerId() {
        return viewerId;
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    /**
     * Offer the newest frame (latest-wins). A closed subscription ignores the frame and never re-populates the
     * slot. Returns {@code true} only if the frame was accepted into the slot (subscription open).
     */
    boolean offer(ViewOnlyFrame frame) {
        Objects.requireNonNull(frame, "frame");
        synchronized (lock) {
            if (closed) {
                return false;
            }
            latest = frame;
        }
        if (frameAvailable != null) {
            try {
                frameAvailable.run();
            } catch (RuntimeException ignored) {
                // a viewer-side listener fault must never break the agent DATA stream
            }
        }
        return true;
    }

    /** Take the latest un-consumed frame, clearing the slot (returns empty when nothing new / closed). */
    public Optional<ViewOnlyFrame> poll() {
        synchronized (lock) {
            ViewOnlyFrame frame = latest;
            latest = null;
            return Optional.ofNullable(frame);
        }
    }

    /** Idempotent — marks closed and clears the slot; no later offer can re-store a frame. */
    public void close() {
        synchronized (lock) {
            closed = true;
            latest = null;
        }
    }
}
