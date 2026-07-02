package com.example.audiogateway.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, memory-only sequence/idempotency state for reconnecting live streams.
 */
public class LiveStreamSequenceTracker {

    public enum Outcome {
        ACCEPTED,
        DUPLICATE,
        GAP,
        CAPACITY_EXCEEDED
    }

    private final int capacity;
    private final ConcurrentMap<String, AtomicLong> lastAcceptedBySession = new ConcurrentHashMap<>();

    public LiveStreamSequenceTracker(final int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("live stream sequence capacity must be positive");
        }
        this.capacity = capacity;
    }

    public Outcome accept(final String sessionId, final long baseline, final long chunkSeq) {
        AtomicLong current = lastAcceptedBySession.get(sessionId);
        if (current == null) {
            if (lastAcceptedBySession.size() >= capacity) {
                return Outcome.CAPACITY_EXCEEDED;
            }
            final AtomicLong candidate = new AtomicLong(baseline);
            final AtomicLong raced = lastAcceptedBySession.putIfAbsent(sessionId, candidate);
            current = raced == null ? candidate : raced;
        }

        while (true) {
            final long previous = current.get();
            if (chunkSeq <= previous) {
                return Outcome.DUPLICATE;
            }
            if (chunkSeq != previous + 1L) {
                return Outcome.GAP;
            }
            if (current.compareAndSet(previous, chunkSeq)) {
                return Outcome.ACCEPTED;
            }
        }
    }

    /**
     * Compensate a just-accepted sequence when the mandatory compute-plane audit
     * fails before any upstream audio is sent.
     */
    public void rollbackAccepted(final String sessionId, final long chunkSeq) {
        final AtomicLong current = lastAcceptedBySession.get(sessionId);
        if (current != null) {
            current.compareAndSet(chunkSeq, chunkSeq - 1L);
        }
    }

    int trackedSessions() {
        return lastAcceptedBySession.size();
    }
}
