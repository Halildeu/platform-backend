package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

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
    private final String operatorDisplayName;
    private final Set<RemoteSessionCapability> requestedCapabilities;
    private final long promptExpiryEpochMillis;
    private final long sessionStartEpochMillis;
    private final AtomicLong seq = new AtomicLong();

    private State state;
    private ConsentLease lease = ConsentLease.NONE;

    RemoteBridgeSession(String sessionId,
                        String transportPeerKey,
                        String deviceId,
                        String operatorSubject,
                        String operatorDisplayName,
                        Set<RemoteSessionCapability> requestedCapabilities,
                        long promptExpiryEpochMillis,
                        long sessionStartEpochMillis,
                        State initialState) {
        this.sessionId = sessionId;
        this.transportPeerKey = transportPeerKey;
        this.deviceId = deviceId;
        this.operatorSubject = operatorSubject;
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

    public synchronized State state() {
        return state;
    }

    public synchronized ConsentLease lease() {
        return lease;
    }

    public long nextSeq() {
        return seq.getAndIncrement();
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
