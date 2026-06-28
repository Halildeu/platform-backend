package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Faz 22.6 #1580 (Codex 019f078a) — one operator viewer's handle on a VIEW_ONLY session's live screen frames.
 *
 * <p><b>Latest-wins, single slot, never blocks.</b> The DATA-plane handler thread {@link #offer(ViewOnlyFrame)}s
 * the newest frame; if the viewer has not consumed the previous one it is overwritten (the OLD frame is dropped,
 * not persisted). A slow or stalled viewer therefore can never apply backpressure to the agent's DATA stream and
 * can never grow an unbounded buffer — the privacy-safe, memory-safe bound for a live screen feed.
 *
 * <p>An optional {@code frameAvailable} listener (set at subscribe time) is invoked AFTER the slot is updated so
 * a transport (slice-3 web viewer) can wake its sender. The listener runs on the DATA thread and MUST NOT block;
 * any throw from it is swallowed here so a viewer fault can never break the agent's DATA stream.
 */
public final class ViewOnlyViewerSubscription {

    private final String sessionId;
    private final String viewerId;
    private final AtomicReference<ViewOnlyFrame> latest = new AtomicReference<>();
    private final Runnable frameAvailable;
    private volatile boolean closed;

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
        return closed;
    }

    /**
     * Offer the newest frame (latest-wins). A closed subscription ignores the frame.
     *
     * @return {@code true} if the frame was accepted into the slot (i.e. the subscription is open)
     */
    boolean offer(ViewOnlyFrame frame) {
        if (closed) {
            return false;
        }
        latest.set(Objects.requireNonNull(frame, "frame"));
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
        return Optional.ofNullable(latest.getAndSet(null));
    }

    /** Idempotent — clears the slot so a held frame is not retained after the viewer detaches. */
    public void close() {
        closed = true;
        latest.set(null);
    }
}
