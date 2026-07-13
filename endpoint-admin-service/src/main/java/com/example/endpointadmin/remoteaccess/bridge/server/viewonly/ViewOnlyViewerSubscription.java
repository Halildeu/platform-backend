package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
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

    private static final int MAX_PENDING_SENT_FRAMES = 32;

    private final String sessionId;
    private final String streamId;
    private final String operatorTenantId;
    private final String operatorSubject;
    private final String viewerId;
    private final Runnable frameAvailable;
    private final Object lock = new Object();
    private final LinkedHashMap<Long, SentFrame> pendingSentFrames = new LinkedHashMap<>();
    private ViewOnlyFrame latest;   // guarded by lock
    private boolean closed;         // guarded by lock
    private long lastRenderedSeq = Long.MIN_VALUE; // guarded by lock
    private long renderedCount;     // guarded by lock

    ViewOnlyViewerSubscription(String sessionId, String streamId, String operatorTenantId,
                               String operatorSubject, String viewerId, Runnable frameAvailable) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.streamId = Objects.requireNonNullElse(streamId, "");
        this.operatorTenantId = Objects.requireNonNullElse(operatorTenantId, "");
        this.operatorSubject = Objects.requireNonNullElse(operatorSubject, "");
        this.viewerId = Objects.requireNonNull(viewerId, "viewerId");
        this.frameAvailable = frameAvailable;
    }

    public String sessionId() {
        return sessionId;
    }

    public String viewerId() {
        return viewerId;
    }

    public String streamId() {
        return streamId;
    }

    boolean isOwnedBy(String tenantId, String subject) {
        return operatorTenantId.equals(tenantId) && operatorSubject.equals(subject);
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

    /**
     * Register a frame after it has been handed to the SSE emitter. Only bounded metadata is retained; frame
     * content never enters this acknowledgement ledger. A closed subscription rejects the registration.
     */
    public boolean markSent(ViewOnlyFrame frame, long sentAtEpochMillis) {
        Objects.requireNonNull(frame, "frame");
        synchronized (lock) {
            if (closed || !sessionId.equals(frame.sessionId())
                    || (!streamId.isEmpty() && !streamId.equals(frame.streamId()))) {
                return false;
            }
            pendingSentFrames.put(frame.frameSeq(),
                    new SentFrame(frame.observedAtEpochMillis(), sentAtEpochMillis));
            while (pendingSentFrames.size() > MAX_PENDING_SENT_FRAMES) {
                Iterator<Long> eldest = pendingSentFrames.keySet().iterator();
                eldest.next();
                eldest.remove();
            }
            return true;
        }
    }

    /**
     * Accept exactly one browser-render acknowledgement for a frame that this live subscription actually sent.
     * Unknown, evicted, duplicate, out-of-order and post-close acknowledgements fail closed.
     */
    public Optional<RenderAcknowledgement> acknowledgeRendered(long frameSeq, long acknowledgedAtEpochMillis) {
        synchronized (lock) {
            if (closed || frameSeq < 0 || frameSeq <= lastRenderedSeq) {
                return Optional.empty();
            }
            SentFrame sent = pendingSentFrames.get(frameSeq);
            if (sent == null || acknowledgedAtEpochMillis < sent.sentAtEpochMillis()) {
                return Optional.empty();
            }
            pendingSentFrames.remove(frameSeq);
            pendingSentFrames.entrySet().removeIf(entry -> entry.getKey() < frameSeq);
            lastRenderedSeq = frameSeq;
            renderedCount++;
            return Optional.of(new RenderAcknowledgement(
                    frameSeq,
                    sent.observedAtEpochMillis(),
                    sent.sentAtEpochMillis(),
                    acknowledgedAtEpochMillis,
                    Math.max(0L, acknowledgedAtEpochMillis - sent.observedAtEpochMillis()),
                    renderedCount == 1L));
        }
    }

    public long renderedCount() {
        synchronized (lock) {
            return renderedCount;
        }
    }

    /** Idempotent — marks closed and clears the slot; no later offer can re-store a frame. */
    public void close() {
        synchronized (lock) {
            closed = true;
            latest = null;
            pendingSentFrames.clear();
        }
    }

    private record SentFrame(long observedAtEpochMillis, long sentAtEpochMillis) {}

    public record RenderAcknowledgement(long frameSeq,
                                        long observedAtEpochMillis,
                                        long sentAtEpochMillis,
                                        long acknowledgedAtEpochMillis,
                                        long endToEndAgeMillis,
                                        boolean firstRenderedFrame) {}
}
