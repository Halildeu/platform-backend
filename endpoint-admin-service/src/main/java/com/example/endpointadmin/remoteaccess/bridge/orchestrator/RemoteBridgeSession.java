package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.MethodStrength;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpChallenge;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpVerification;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.Event;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.Transition;
import com.example.endpointadmin.remoteaccess.bridge.contract.ConsentLease;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Faz 22.6 T-4a-i (Codex 019ebbfa P1) — ONE remote-support session's broker-side state. Every lifecycle
 * change goes through the T-1b {@link RemoteBridgeSessionStateMachine} (total, fail-closed — state is never
 * hand-set); the transport binding ({@code transportPeerKey}) is immutable; the consent-prompt expiry is
 * BROKER-owned (set at open time, never from the agent); the permit sequence is monotonic.
 *
 * <p>Consent: {@link #grantConsent} clamps the lease expiry to
 * {@code min(broker promptExpiry, agent-reported expiry)} — a compromised agent can SHORTEN its own consent
 * window but can never extend it past what the broker offered (Codex P4).
 */
public final class RemoteBridgeSession {

    private final RemoteBridgeSessionStateMachine machine = new RemoteBridgeSessionStateMachine();

    private final String sessionId;
    private final String transportPeerKey;
    private final String deviceId;
    private final String operatorSubject;
    private final String operatorTenantId;
    private final String operatorDisplayName;
    private final Set<RemoteSessionCapability> requestedCapabilities;
    private final long promptExpiryEpochMillis;
    private final long sessionStartEpochMillis;
    private final AtomicLong seq = new AtomicLong(1L);

    private State state;
    private ConsentLease lease = ConsentLease.NONE;
    // operator step-up freshness/strength (Faz 22.6 D step-up wiring) — default fail-closed weakest until the
    // operator transport records a VERIFIED step-up (slice-4c). Restart drops it (fail-closed, session-scoped).
    private long lastStepUpEpochMillis = 0L;
    private MethodStrength stepUpStrength = MethodStrength.NONE;
    // a single pending step-up challenge (issued, awaiting the operator's assertion) + its expiry; null = none.
    // One pending challenge per session (Codex S3) — a new issue replaces the prior, verify consumes it.
    private StepUpChallenge pendingStepUpChallenge = null;
    private long pendingChallengeExpiryEpochMillis = 0L;

    RemoteBridgeSession(String sessionId,
                        String transportPeerKey,
                        String deviceId,
                        String operatorSubject,
                        String operatorTenantId,
                        String operatorDisplayName,
                        Set<RemoteSessionCapability> requestedCapabilities,
                        long promptExpiryEpochMillis,
                        long sessionStartEpochMillis,
                        State initialState) {
        this.sessionId = sessionId;
        this.transportPeerKey = transportPeerKey;
        this.deviceId = deviceId;
        this.operatorSubject = operatorSubject;
        this.operatorTenantId = operatorTenantId;
        this.operatorDisplayName = operatorDisplayName;
        this.requestedCapabilities = Set.copyOf(requestedCapabilities);
        this.promptExpiryEpochMillis = promptExpiryEpochMillis;
        this.sessionStartEpochMillis = sessionStartEpochMillis;
        this.state = initialState;
    }

    /** Drive the state machine; the state advances ONLY when the machine accepts. */
    public synchronized Transition transition(Event event) {
        Transition transition = machine.transition(state, event);
        if (transition.accepted()) {
            state = transition.to();
        }
        return transition;
    }

    /**
     * Record the endpoint user's consent as a LEASE whose expiry is clamped to the broker's own prompt
     * window. Returns false (and leaves {@link ConsentLease#NONE}) when the result is a denial.
     */
    public synchronized boolean grantConsent(boolean granted, long agentReportedExpiryEpochMillis) {
        if (!granted) {
            lease = ConsentLease.NONE;
            return false;
        }
        long clamped = Math.min(promptExpiryEpochMillis,
                agentReportedExpiryEpochMillis > 0 ? agentReportedExpiryEpochMillis : promptExpiryEpochMillis);
        lease = new ConsentLease(true, false, clamped);
        return true;
    }

    /** The endpoint user withdrew consent locally — the lease aborts (fail-closed for every later check). */
    public synchronized void abortLeaseLocally() {
        if (lease.granted()) {
            lease = new ConsentLease(true, true, lease.expiryEpochMillis());
        }
    }

    /**
     * Faz 22.6 D step-up wiring (Codex 019ebe06) — record a VERIFIED operator step-up into the session's
     * freshness/strength state (the {@code OperatorStepUpPolicy} CONSUMER reads it via the assembler). The
     * caller that produces the {@link StepUpVerification} from a real WebAuthn assertion is the operator-facing
     * transport (slice-4c) — until then this is never called and the session stays at the fail-closed weakest.
     *
     * <p><b>VERIFIED-only advance + monotonic guard (Codex S2):</b> a non-VERIFIED verification is a no-op (a
     * failed attempt must NOT erase an earlier valid step-up); a VERIFIED one is accepted only when its
     * timestamp is at/after the session start AND does not move the freshness backward — a pre-session or
     * backward timestamp is nonsensical/forgeable, so it is a fail-closed no-op.
     */
    public synchronized void recordStepUp(StepUpVerification verification) {
        if (verification == null || !verification.isVerified()) {
            return;
        }
        long ts = verification.verifiedAtEpochMillis();
        if (ts < sessionStartEpochMillis || ts < lastStepUpEpochMillis) {
            return;
        }
        lastStepUpEpochMillis = ts;
        stepUpStrength = verification.achievedStrength();
    }

    /**
     * Issue a single pending step-up challenge — a new one REPLACES any prior (one pending per session, Codex
     * S3), so a stale or parallel challenge can never be redeemed. The handler stores the challenge it just
     * generated here; {@link #consumePendingStepUpChallenge} redeems it exactly once.
     */
    public synchronized void setPendingStepUpChallenge(StepUpChallenge challenge, long expiryEpochMillis) {
        this.pendingStepUpChallenge = challenge;
        this.pendingChallengeExpiryEpochMillis = expiryEpochMillis;
    }

    /**
     * Consume the pending step-up challenge (SINGLE-USE): returns it ONLY if present and not expired at {@code
     * now}, then clears it unconditionally. A missing/expired/already-consumed challenge yields empty
     * (fail-closed — the handler then refuses the assertion without advancing the step-up).
     */
    public synchronized java.util.Optional<StepUpChallenge> consumePendingStepUpChallenge(long nowEpochMillis) {
        StepUpChallenge challenge = pendingStepUpChallenge;
        long expiry = pendingChallengeExpiryEpochMillis;
        pendingStepUpChallenge = null; // single-use: always cleared on consume, even when expired
        pendingChallengeExpiryEpochMillis = 0L;
        if (challenge == null || nowEpochMillis > expiry) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(challenge);
    }

    public synchronized long lastStepUpEpochMillis() {
        return lastStepUpEpochMillis;
    }

    public synchronized MethodStrength stepUpStrength() {
        return stepUpStrength;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized ConsentLease lease() {
        return lease;
    }

    public long nextSeq() {
        return seq.getAndIncrement();
    }

    /**
     * The next broker-owned permit sequence without consuming it. Used by bounded non-prod acceptance probes
     * to avoid minting replay evidence before a normal product operation has advanced the session.
     */
    public long nextSeqValue() {
        return seq.get();
    }

    public String sessionId() {
        return sessionId;
    }

    public String transportPeerKey() {
        return transportPeerKey;
    }

    public String deviceId() {
        return deviceId;
    }

    public String operatorSubject() {
        return operatorSubject;
    }

    /**
     * Faz 22.6 slice-4c-2b-2b (Codex REVISE) — the operator's tenant, pinned from the AUTHENTICATED identity at
     * open time (never a wire field). The follow-up operations (step-up, operation) gate ownership on tenant AND
     * subject, so an operator with the SAME subject in a DIFFERENT tenant cannot act on this session.
     */
    public String operatorTenantId() {
        return operatorTenantId;
    }

    public String operatorDisplayName() {
        return operatorDisplayName;
    }

    public Set<RemoteSessionCapability> requestedCapabilities() {
        return requestedCapabilities;
    }

    public long promptExpiryEpochMillis() {
        return promptExpiryEpochMillis;
    }

    public long sessionStartEpochMillis() {
        return sessionStartEpochMillis;
    }

    public synchronized boolean isTerminal() {
        return state.isTerminal();
    }
}
