package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.ApprovalFatigueLimiter.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Faz 22.6 D10-E10 (Codex 019ebe06) — the approval-fatigue limiter caps approvals per (canonical) approver in
 * a sliding window: under the cap is ALLOWED, at/over it is FATIGUED (fail-closed, not recorded so the window
 * slides cleanly), a blank approver is DENIED_INVALID, and the cap is per-approver + slides as old approvals
 * age out.
 */
class ApprovalFatigueLimiterTest {

    private static final long WINDOW = 60_000L;

    @Test
    void underTheCapIsAllowedAtTheCapIsFatigued() {
        ApprovalFatigueLimiter limiter = new ApprovalFatigueLimiter(2, WINDOW); // 2 approvals / window
        assertEquals(Decision.ALLOWED, limiter.recordApproval("u1", 1_000L));
        assertEquals(Decision.ALLOWED, limiter.recordApproval("u1", 2_000L));
        // the 3rd within the window is over the cap → fatigued (fail-closed)
        assertEquals(Decision.FATIGUED, limiter.recordApproval("u1", 3_000L));
        assertEquals(Decision.FATIGUED, limiter.recordApproval("u1", 4_000L));
    }

    @Test
    void theWindowSlidesSoAnAgedOutApprovalFreesCapacity() {
        ApprovalFatigueLimiter limiter = new ApprovalFatigueLimiter(2, WINDOW);
        assertEquals(Decision.ALLOWED, limiter.recordApproval("u1", 1_000L));
        assertEquals(Decision.ALLOWED, limiter.recordApproval("u1", 2_000L));
        assertEquals(Decision.FATIGUED, limiter.recordApproval("u1", 10_000L)); // still 2 in-window
        // advance past the window for the first two approvals (1_000 + WINDOW = 61_000) → they age out
        assertEquals(Decision.ALLOWED, limiter.recordApproval("u1", 61_001L));
    }

    @Test
    void fatiguedAttemptsAreNotRecordedSoNoPenaltyAccrues() {
        ApprovalFatigueLimiter limiter = new ApprovalFatigueLimiter(1, WINDOW); // 1 / window
        assertEquals(Decision.ALLOWED, limiter.recordApproval("u1", 1_000L));
        // many fatigued attempts must NOT push the aged-out point further (they are not recorded)
        for (int i = 0; i < 5; i++) {
            assertEquals(Decision.FATIGUED, limiter.recordApproval("u1", 2_000L + i));
        }
        // once the single recorded approval ages out, capacity returns
        assertEquals(Decision.ALLOWED, limiter.recordApproval("u1", 1_000L + WINDOW + 1));
    }

    @Test
    void theCapIsPerApprover() {
        ApprovalFatigueLimiter limiter = new ApprovalFatigueLimiter(1, WINDOW);
        assertEquals(Decision.ALLOWED, limiter.recordApproval("u1", 1_000L));
        assertEquals(Decision.FATIGUED, limiter.recordApproval("u1", 2_000L));
        // a different approver has their own independent window
        assertEquals(Decision.ALLOWED, limiter.recordApproval("u2", 2_000L));
    }

    @Test
    void aBlankOrNullApproverIsFailClosed() {
        ApprovalFatigueLimiter limiter = new ApprovalFatigueLimiter(2, WINDOW);
        assertEquals(Decision.DENIED_INVALID, limiter.recordApproval(null, 1_000L));
        assertEquals(Decision.DENIED_INVALID, limiter.recordApproval("  ", 1_000L));
    }

    @Test
    void theApproverIsTrimmedSoWhitespaceDoesNotEvadeTheCap() {
        ApprovalFatigueLimiter limiter = new ApprovalFatigueLimiter(1, WINDOW);
        assertEquals(Decision.ALLOWED, limiter.recordApproval("u1", 1_000L));
        // " u1 " is the SAME approver — must not get a fresh window
        assertEquals(Decision.FATIGUED, limiter.recordApproval(" u1 ", 2_000L));
    }

    @Test
    void theConstructorValidatesItsBounds() {
        assertThrows(IllegalArgumentException.class, () -> new ApprovalFatigueLimiter(0, WINDOW));
        assertThrows(IllegalArgumentException.class, () -> new ApprovalFatigueLimiter(2, 0));
    }
}
