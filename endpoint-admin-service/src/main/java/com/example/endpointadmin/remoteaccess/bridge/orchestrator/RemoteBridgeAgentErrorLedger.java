package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.AgentErrorFrame;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded in-memory index of recent agent CONTROL error frames. This is not a long-term audit store; the
 * durable audit chain still receives the bounded {@code AGENT_ERROR:*} event through {@link BrokerControlPlane}.
 * The ledger exists so non-prod acceptance probes can prove that a broker-sent negative permit reached the
 * real agent and was denied by the product gate.
 */
public final class RemoteBridgeAgentErrorLedger {

    public record Observation(String sessionId,
                              String transportPeerKey,
                              String code,
                              boolean retryable,
                              long observedAtEpochMillis) {
    }

    private final int maxEntries;
    private final Deque<Observation> observations = new ArrayDeque<>();

    public RemoteBridgeAgentErrorLedger(int maxEntries) {
        if (maxEntries < 0) {
            throw new IllegalArgumentException("maxEntries must not be negative");
        }
        this.maxEntries = maxEntries;
    }

    public synchronized void record(PeerIdentity peer, AgentErrorFrame error, long nowEpochMillis) {
        if (maxEntries == 0 || peer == null || error == null
                || error.sessionId() == null || error.sessionId().isBlank()
                || error.code() == null || error.code().isBlank()) {
            return;
        }
        observations.addLast(new Observation(error.sessionId(), peer.transportPeerKey(), error.code().trim(),
                error.retryable(), nowEpochMillis));
        while (observations.size() > maxEntries) {
            observations.removeFirst();
        }
    }

    public synchronized Optional<Observation> findAfter(String sessionId,
                                                        String transportPeerKey,
                                                        String code,
                                                        long notBeforeEpochMillis) {
        if (isBlank(sessionId) || isBlank(transportPeerKey) || isBlank(code)) {
            return Optional.empty();
        }
        return observations.stream()
                .filter(o -> o.observedAtEpochMillis() >= notBeforeEpochMillis)
                .filter(o -> Objects.equals(sessionId, o.sessionId()))
                .filter(o -> Objects.equals(transportPeerKey, o.transportPeerKey()))
                .filter(o -> Objects.equals(code, o.code()))
                .reduce((first, second) -> second);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
