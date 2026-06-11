package com.example.endpointadmin.remoteaccess;

import java.time.Duration;
import java.time.Instant;

/**
 * Faz 22.6 B2.2 — the heartbeat orchestrator: the "brain" of the continuous-re-evaluation / hard-kill loop
 * (ADR-0033 §9b, Codex 019eb54b criteria #2/#4/#5/#6). Pure + total — NO scheduling, NO I/O beyond the
 * injected {@link TokenLifecycleStore} read; the live {@code @Scheduled} driver + the prod store backing
 * (Redis Lua / DB CAS) are B2.2b. Disabled-by-default.
 *
 * <p>Per sample it: (0) <b>heartbeat-timeout kill</b> — a live session whose last fresh sample is older
 * than {@code maxHeartbeatAge} is killed even if no fresh sample arrives (seq-independent, fail-closed),
 * (1) rejects a stale/out-of-order sample (monotonicity — can't rewind a killed session), (2) refreshes
 * {@code tokenBound} from the authoritative store with the precise root cause, (3) re-evaluates the ACTIVE
 * invariant, (4) on a kill measures {@code revokedAt → now} latency (clamped ≥ 0; a negative skew is
 * flagged, not silently zeroed) for the {@code revocation_latency_ms} SLO (P95 ≤ 5s).
 */
public final class RemoteSessionHeartbeat {

    private final TokenLifecycleStore store;
    private final RemoteSessionStateMachine stateMachine;
    private final Duration maxHeartbeatAge;

    public RemoteSessionHeartbeat(TokenLifecycleStore store, RemoteSessionStateMachine stateMachine,
                                  Duration maxHeartbeatAge) {
        this.store = store;
        this.stateMachine = stateMachine;
        this.maxHeartbeatAge = maxHeartbeatAge;
    }

    /**
     * @param lastFreshAt when the last FRESH sample was applied (heartbeat-timeout anchor; nullable)
     */
    public record SessionSnapshot(
            String sessionId, String jti, RemoteSessionState state, long lastAppliedSeq, Instant lastFreshAt) {
    }

    /** @param revokedAt the t0 of a pending revocation (for SLO latency), or {@code null} if none */
    public record PreconditionSample(
            boolean policyAllow,
            boolean targetConsent,
            boolean dualApproval,
            boolean agentAttestation,
            boolean recordingWriterAck,
            Instant revokedAt) {
    }

    /**
     * @param applied       false iff the sample was rejected as stale (no state change — anti-rewind)
     * @param latencyMillis t0→decision latency (clamped ≥ 0) when this produced a kill, else 0
     * @param clockSkew     true iff {@code now < revokedAt} on a kill (the latency is unreliable → meter it)
     */
    public record HeartbeatDecision(
            RemoteSessionState target,
            RemoteSessionStateMachine.KillReason reason,
            boolean kill,
            boolean applied,
            long latencyMillis,
            boolean clockSkew) {
    }

    public HeartbeatDecision evaluate(SessionSnapshot snapshot, PreconditionSample sample,
                                      long sampleSeq, Instant now) {
        if (snapshot == null || now == null) {
            return new HeartbeatDecision(RemoteSessionState.ABORTED,
                    RemoteSessionStateMachine.KillReason.VISIBILITY_LOSS, true, true, 0, false);
        }
        boolean live = snapshot.state() == RemoteSessionState.ACTIVE;

        // (0) seq-independent heartbeat-timeout kill: a live session that stopped getting fresh samples
        // must die even if nothing fresh arrives (Codex absorb — no indefinitely-alive stale session).
        if (live && snapshot.lastFreshAt() != null
                && Duration.between(snapshot.lastFreshAt(), now).compareTo(maxHeartbeatAge) > 0) {
            return new HeartbeatDecision(RemoteSessionState.ABORTED,
                    RemoteSessionStateMachine.KillReason.HEARTBEAT_TIMEOUT, true, true, 0, false);
        }
        if (sample == null) {
            // malformed heartbeat for a live session → fail-closed visibility loss
            return new HeartbeatDecision(
                    live ? RemoteSessionState.ABORTED : snapshot.state(),
                    live ? RemoteSessionStateMachine.KillReason.VISIBILITY_LOSS
                            : RemoteSessionStateMachine.KillReason.NOT_ACTIVE,
                    live, true, 0, false);
        }
        // (1) monotonicity: a stale/out-of-order sample never even reads the store — and never rewinds state.
        if (!RemoteSessionStateMachine.isFreshSample(sampleSeq, snapshot.lastAppliedSeq())) {
            return new HeartbeatDecision(snapshot.state(),
                    RemoteSessionStateMachine.KillReason.NONE, false, false, 0, false);
        }
        // (2) authoritative token liveness with the precise root cause.
        TokenLifecycleStore.TokenLiveCheckResult liveResult = store.isTokenLive(snapshot.jti(), now);
        RemoteSessionPreconditions current = new RemoteSessionPreconditions(
                sample.policyAllow(), sample.targetConsent(), sample.dualApproval(),
                liveResult.isLive(), sample.agentAttestation(), sample.recordingWriterAck());
        // (3) re-evaluate.
        RemoteSessionStateMachine.Reevaluation reev = stateMachine.reevaluateActive(snapshot.state(), current);
        RemoteSessionStateMachine.KillReason reason = refineTokenReason(reev.reason(), liveResult);

        // (4) latency for the SLO — clamp ≥ 0, flag clock skew rather than silently zeroing.
        long latency = 0;
        boolean skew = false;
        if (reev.isKill() && sample.revokedAt() != null) {
            long delta = Duration.between(sample.revokedAt(), now).toMillis();
            if (delta < 0) {
                skew = true; // now < revokedAt (clock skew / out-of-order) → meter revocation_clock_skew
            } else {
                latency = delta;
            }
        }
        return new HeartbeatDecision(reev.target(), reason, reev.isKill(), true, latency, skew);
    }

    /**
     * When the kill is due to token-loss ({@code TOKEN_REVOKED} from the state machine), refine it to the
     * precise cause the store reported, so audit/IR sees REVOKED vs EXPIRED vs STORE_UNAVAILABLE vs
     * NOT_FOUND rather than mislabeling a partition as a revocation. Non-token kill reasons pass through.
     */
    private static RemoteSessionStateMachine.KillReason refineTokenReason(
            RemoteSessionStateMachine.KillReason base, TokenLifecycleStore.TokenLiveCheckResult liveResult) {
        if (base != RemoteSessionStateMachine.KillReason.TOKEN_REVOKED) {
            return base; // a higher-precedence guarantee (policy/attestation/recorder/consent) was lost
        }
        return switch (liveResult) {
            case REVOKED -> RemoteSessionStateMachine.KillReason.TOKEN_REVOKED;
            case EXPIRED -> RemoteSessionStateMachine.KillReason.TOKEN_EXPIRED;
            case STORE_UNAVAILABLE -> RemoteSessionStateMachine.KillReason.STORE_UNAVAILABLE;
            case NOT_FOUND, INVALID -> RemoteSessionStateMachine.KillReason.TOKEN_NOT_FOUND;
            case LIVE -> RemoteSessionStateMachine.KillReason.TOKEN_REVOKED; // unreachable (live ⇒ no kill)
        };
    }
}
