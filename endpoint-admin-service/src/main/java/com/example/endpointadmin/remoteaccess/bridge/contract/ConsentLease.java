package com.example.endpointadmin.remoteaccess.bridge.contract;

/**
 * Faz 22.6 T-1a — the attended-consent LEASE (ADR-0034 D6, ADR-0038 §6). Attended consent is a transport
 * PRECONDITION, not UX: the endpoint user's approval is a time-bounded, locally-revocable lease, NOT a
 * one-shot "granted" boolean. The broker admits a VIEW_ONLY frame or a PTY operation ONLY while the lease is
 * active — present, not locally aborted, and not expired at the evaluation instant (Codex 019eb9fb: "no active
 * consent lease → deny; consent result alone is not enough").
 *
 * <p>Fail-closed: {@code locallyAborted} (the endpoint user pressed abort, or the persistent indicator dropped)
 * immediately deactivates the lease regardless of expiry; an absent ({@code null}) lease is never active.
 *
 * @param granted          whether the endpoint user approved the session
 * @param locallyAborted   whether the agent has signalled a local abort / indicator-loss (kills the lease)
 * @param expiryEpochMillis the instant the consent lapses and must be re-prompted
 */
public record ConsentLease(boolean granted, boolean locallyAborted, long expiryEpochMillis) {

    /** A lease that never admits anything — the fail-closed default before consent. */
    public static final ConsentLease NONE = new ConsentLease(false, false, 0L);

    /**
     * Active iff granted, not locally aborted, and {@code now} is before expiry. {@code null} is never active
     * (a missing lease must not silently admit a frame/operation).
     */
    public boolean isActive(long nowEpochMillis) {
        return granted && !locallyAborted && nowEpochMillis >= 0 && nowEpochMillis < expiryEpochMillis;
    }

    /** Null-safe accessor — an absent lease is the fail-closed {@link #NONE}. */
    public static boolean isActive(ConsentLease lease, long nowEpochMillis) {
        return lease != null && lease.isActive(nowEpochMillis);
    }
}
