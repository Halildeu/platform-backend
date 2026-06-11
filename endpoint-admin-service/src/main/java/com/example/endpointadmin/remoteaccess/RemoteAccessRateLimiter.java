package com.example.endpointadmin.remoteaccess;

/**
 * Faz 22.6 B2 — 3-axis rate limiter for connect/validation attempts (Codex 019eb54b criterion #9):
 * an attempt must stay under the threshold on EACH of operator, source-network, and session/device.
 * Combined with the uniform-{@code DENIED} constant-time response (A2), this blocks brute-force /
 * enumeration / replay-retry DoS without leaking which axis tripped to the client.
 */
public interface RemoteAccessRateLimiter {

    /** Which axis (if any) tripped. {@link #ALLOWED} = under all thresholds. */
    enum RateLimitDecision {
        ALLOWED,
        THROTTLED_OPERATOR,
        THROTTLED_NETWORK,
        THROTTLED_SESSION;

        public boolean isAllowed() {
            return this == ALLOWED;
        }
    }

    /**
     * Record + evaluate one attempt across all three axes. The specific tripped axis is internal/audit
     * only — callers surface a uniform throttle to the client (no oracle).
     *
     * @param operatorUserId   the operator principal
     * @param sourceNetwork    a coarse network key (e.g. /24 or ASN bucket) — NOT a raw client IP in logs
     * @param sessionDeviceKey session-or-device scoped key
     */
    RateLimitDecision tryAcquire(String operatorUserId, String sourceNetwork, String sessionDeviceKey);
}
