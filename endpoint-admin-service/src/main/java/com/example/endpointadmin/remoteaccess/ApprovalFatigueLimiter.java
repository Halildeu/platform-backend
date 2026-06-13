package com.example.endpointadmin.remoteaccess;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Faz 22.6 D10-E10 (Codex 019ebe06) — approval-fatigue limiter for dual-control (ADR-0034 §11 must-land #10:
 * "+ approval-fatigue limits"). Caps how many session-request approvals a single approver may grant within a
 * sliding window, so an approver cannot rubber-stamp an unbounded stream of requests (approval fatigue is a
 * known dual-control failure mode — the second control degenerates into a formality under volume).
 *
 * <p>The approver is the CANONICAL subject (resolve via {@link CanonicalIdentityResolver} first, so an
 * approver cannot evade the cap by approving under aliases / proxies / service-accounts). Pairs with
 * {@link RemoteSessionAuthz#approverDistinctFromRequesterCanonical} — distinctness AND volume are both
 * enforced on the canonical subject.
 *
 * <p><b>Fail-closed, in-memory reference:</b> a null/blank approver is refused; at-or-over the cap yields
 * {@link Decision#FATIGUED} (and is NOT recorded, so the window slides cleanly rather than penalizing).
 * Synchronized (the per-approver window read-evict-append must be atomic). The DURABLE/distributed store (so
 * the cap holds across broker replicas + restarts) is the owner-gated live slice; this reference proves the
 * semantics disabled-by-default.
 */
public final class ApprovalFatigueLimiter {

    /** Whether an approval may proceed under the per-approver fatigue cap. */
    public enum Decision {
        /** Under the cap — the approval is recorded and may proceed. */
        ALLOWED,
        /** At/over the per-window cap — refuse (fail-closed); the approver must wait for the window to slide. */
        FATIGUED,
        /** A null/blank approver — fail-closed (an unidentifiable approver can never pass dual-control). */
        DENIED_INVALID
    }

    private final int maxApprovalsPerWindow;
    private final long windowMillis;
    private final Map<String, Deque<Long>> approvalsByApprover = new HashMap<>();

    /**
     * @param maxApprovalsPerWindow the most approvals one approver may grant per window (must be positive)
     * @param windowMillis          the sliding-window length in millis (must be positive)
     */
    public ApprovalFatigueLimiter(int maxApprovalsPerWindow, long windowMillis) {
        if (maxApprovalsPerWindow <= 0) {
            throw new IllegalArgumentException("maxApprovalsPerWindow must be positive");
        }
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be positive");
        }
        this.maxApprovalsPerWindow = maxApprovalsPerWindow;
        this.windowMillis = windowMillis;
    }

    /**
     * Record an approval by the (canonical) approver if it is under the per-window cap. ALLOWED records the
     * approval; FATIGUED does NOT record (the window slides cleanly); a blank approver is DENIED_INVALID.
     */
    public synchronized Decision recordApproval(String approverCanonical, long nowEpochMillis) {
        if (approverCanonical == null || approverCanonical.isBlank()) {
            return Decision.DENIED_INVALID;
        }
        Deque<Long> window = approvalsByApprover.computeIfAbsent(approverCanonical.strip(), key -> new ArrayDeque<>());
        long cutoff = nowEpochMillis - windowMillis;
        // evict approvals that have aged out of the sliding window (strictly older than the cutoff)
        while (!window.isEmpty() && window.peekFirst() <= cutoff) {
            window.pollFirst();
        }
        if (window.size() >= maxApprovalsPerWindow) {
            return Decision.FATIGUED; // at/over the cap — refuse, do NOT record (no penalty accrual)
        }
        window.addLast(nowEpochMillis);
        return Decision.ALLOWED;
    }
}
