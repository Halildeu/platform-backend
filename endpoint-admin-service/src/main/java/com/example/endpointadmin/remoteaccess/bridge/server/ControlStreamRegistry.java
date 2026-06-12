package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.proto.ChannelType;
import com.example.endpointadmin.remoteaccess.bridge.proto.Envelope;
import com.example.endpointadmin.remoteaccess.bridge.proto.Kill;
import io.grpc.stub.StreamObserver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Faz 22.6 T-2b (Codex 019eb9fb) — the live CONTROL streams, keyed by the AUTHENTICATED
 * {@link PeerIdentity#transportPeerKey()} — NEVER by an advisory {@code AgentHello.deviceId} (a lying agent
 * must not be able to claim another device's slot; the verified deviceId→peer mapping is the broker's T-4
 * job, after B1.4 cert binding).
 *
 * <p>One CONTROL stream per authenticated peer: a reconnect REPLACES the previous stream (the old one is
 * completed and dropped — only for the SAME authenticated peer). {@link #killPeer} is THE sub-second kill
 * path: it pushes {@code Envelope.kill} onto CONTROL and then terminates the stream — CONTROL is a separate
 * HTTP/2 stream from DATA, so DATA backpressure can never queue ahead of it (tested).
 */
public final class ControlStreamRegistry {

    private final Map<String, StreamObserver<Envelope>> streams = new ConcurrentHashMap<>();

    /** Register the peer's CONTROL stream; an existing stream for the SAME peer is completed and replaced. */
    public void register(PeerIdentity peer, StreamObserver<Envelope> stream) {
        StreamObserver<Envelope> previous = streams.put(peer.transportPeerKey(), stream);
        if (previous != null && previous != stream) {
            completeQuietly(previous);
        }
    }

    /** Remove the peer's stream — only if it is still THIS stream (a replaced stream must not unregister its successor). */
    public void unregister(PeerIdentity peer, StreamObserver<Envelope> stream) {
        streams.remove(peer.transportPeerKey(), stream);
    }

    public boolean isConnected(String transportPeerKey) {
        return streams.containsKey(transportPeerKey);
    }

    public int connectedCount() {
        return streams.size();
    }

    /**
     * The transport-level kill is peer-scoped, not session-scoped — when no broker session id exists yet
     * (T-4 wiring), the kill still must satisfy the T-2a wire contract (a Kill's sessionId is a REQUIRED
     * valid id, so the agent-side adapter never rejects an emergency kill).
     */
    public static final String TRANSPORT_KILL_SESSION = "transport-kill";

    /**
     * KILL the authenticated peer's session NOW: push {@code Envelope.kill} on CONTROL, then complete and
     * unregister the stream (terminal — Codex T-2b guidance). Returns false when the peer has no live
     * CONTROL stream. A failed push still unregisters (the stream is dead either way; the safe outcome is
     * removal, and the T-3 agent's heartbeat loss handling is the backstop).
     *
     * @param sessionId the broker session this kill targets, or null/blank for a peer-scoped transport kill
     *                  ({@link #TRANSPORT_KILL_SESSION})
     */
    public boolean killPeer(String transportPeerKey, String sessionId, String killReason, long nowEpochMillis) {
        StreamObserver<Envelope> stream = streams.remove(transportPeerKey);
        if (stream == null) {
            return false;
        }
        String session = sessionId == null || sessionId.isBlank() ? TRANSPORT_KILL_SESSION : sessionId;
        Envelope kill = Envelope.newBuilder()
                .setChannelType(ChannelType.CONTROL)
                .setSessionId(session)
                .setSentAtEpochMillis(nowEpochMillis)
                .setKill(Kill.newBuilder()
                        .setSessionId(session)
                        .setKillReason(killReason == null || killReason.isBlank() ? "killed" : killReason)
                        .setIssuedAtEpochMillis(nowEpochMillis))
                .build();
        try {
            stream.onNext(kill);
            stream.onCompleted();
            return true;
        } catch (RuntimeException e) {
            completeQuietly(stream);
            return true; // the stream is gone either way — terminal outcome reached
        }
    }

    /** Complete every live stream (server shutdown). */
    public void completeAll() {
        streams.values().forEach(ControlStreamRegistry::completeQuietly);
        streams.clear();
    }

    private static void completeQuietly(StreamObserver<Envelope> stream) {
        try {
            stream.onCompleted();
        } catch (RuntimeException ignored) {
            // already terminated — removal is the outcome that matters
        }
    }
}
