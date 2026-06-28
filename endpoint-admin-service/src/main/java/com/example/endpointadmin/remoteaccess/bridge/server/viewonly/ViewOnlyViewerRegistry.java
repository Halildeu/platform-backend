package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Faz 22.6 #1580 (Codex 019f078a) — the broker→operator VIEW_ONLY fanout seam. It is the ONLY thing that turns
 * an accepted agent DATA frame into a live operator view, and it is deliberately one-directional and
 * control-free:
 *
 * <ul>
 *   <li><b>No-control invariant.</b> This class (and the whole {@code ...bridge.server.viewonly} package) imports
 *       nothing from the control plane — no control-stream registry, no permit, no operation dispatch. A viewer
 *       can only RECEIVE screen frames; there is no path from a viewer back to the agent. A test asserts the
 *       package carries no control-plane import.</li>
 *   <li><b>Bounded fanout.</b> At most {@code maxViewersPerSession} viewers per session (default 1 — the
 *       one-to-one pilot invariant); {@link #subscribe} returns empty once the bound is reached.</li>
 *   <li><b>Latest-wins, never blocks.</b> {@link #publish} offers each subscriber the newest frame with
 *       single-slot latest-wins backpressure (see {@link ViewOnlyViewerSubscription}); a slow viewer never
 *       delays the DATA stream.</li>
 *   <li><b>No persistence.</b> A frame with no subscribed viewer is simply dropped — there is no buffer, no
 *       queue, no store.</li>
 * </ul>
 */
public final class ViewOnlyViewerRegistry {

    private final int maxViewersPerSession;
    private final ConcurrentMap<String, CopyOnWriteArrayList<ViewOnlyViewerSubscription>> bySession =
            new ConcurrentHashMap<>();
    private final AtomicLong viewerSeq = new AtomicLong();

    public ViewOnlyViewerRegistry(int maxViewersPerSession) {
        this.maxViewersPerSession = maxViewersPerSession <= 0 ? 1 : maxViewersPerSession;
    }

    public int maxViewersPerSession() {
        return maxViewersPerSession;
    }

    /**
     * Subscribe an operator viewer to a session's live screen frames.
     *
     * @param frameAvailable optional, non-blocking wake hook invoked after each newly offered frame (nullable)
     * @return the subscription, or empty if the per-session viewer bound is already reached
     */
    public Optional<ViewOnlyViewerSubscription> subscribe(String sessionId, Runnable frameAvailable) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        CopyOnWriteArrayList<ViewOnlyViewerSubscription> subs =
                bySession.computeIfAbsent(sessionId, s -> new CopyOnWriteArrayList<>());
        // bound check + add under the per-session list monitor so two concurrent subscribers cannot both pass
        synchronized (subs) {
            subs.removeIf(ViewOnlyViewerSubscription::isClosed);
            if (subs.size() >= maxViewersPerSession) {
                return Optional.empty();
            }
            ViewOnlyViewerSubscription subscription = new ViewOnlyViewerSubscription(
                    sessionId, "vw-" + viewerSeq.incrementAndGet(), frameAvailable);
            subs.add(subscription);
            return Optional.of(subscription);
        }
    }

    /**
     * Fan a frame out to the session's subscribed viewers (latest-wins each).
     *
     * @return the number of OPEN viewers the frame was delivered to (0 = dropped, no viewer)
     */
    public int publish(ViewOnlyFrame frame) {
        Objects.requireNonNull(frame, "frame");
        List<ViewOnlyViewerSubscription> subs = bySession.get(frame.sessionId());
        if (subs == null || subs.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        for (ViewOnlyViewerSubscription sub : subs) {
            if (sub.offer(frame)) {
                delivered++;
            }
        }
        return delivered;
    }

    /** Current OPEN viewer count for a session (closed entries excluded) — observability / tests. */
    public int viewerCount(String sessionId) {
        List<ViewOnlyViewerSubscription> subs = bySession.get(sessionId);
        if (subs == null) {
            return 0;
        }
        return (int) subs.stream().filter(s -> !s.isClosed()).count();
    }

    /** Detach one viewer (idempotent). */
    public void unsubscribe(ViewOnlyViewerSubscription subscription) {
        if (subscription == null) {
            return;
        }
        subscription.close();
        CopyOnWriteArrayList<ViewOnlyViewerSubscription> subs = bySession.get(subscription.sessionId());
        if (subs != null) {
            subs.remove(subscription);
            subs.removeIf(ViewOnlyViewerSubscription::isClosed);
            bySession.computeIfPresent(subscription.sessionId(), (s, list) -> list.isEmpty() ? null : list);
        }
    }

    /** Detach every viewer for a session (called when the session ends — no stale viewer slot). */
    public void closeSession(String sessionId) {
        CopyOnWriteArrayList<ViewOnlyViewerSubscription> subs = bySession.remove(sessionId);
        if (subs != null) {
            subs.forEach(ViewOnlyViewerSubscription::close);
        }
    }
}
