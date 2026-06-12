package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.contract.OperationPermit;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.proto.ChannelType;
import com.example.endpointadmin.remoteaccess.bridge.proto.Envelope;
import com.example.endpointadmin.remoteaccess.bridge.proto.Kill;
import com.example.endpointadmin.remoteaccess.bridge.wire.RemoteBridgeProtoAdapter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Faz 22.6 T-2b (Codex 019eb9fb) — the live CONTROL streams, keyed by the AUTHENTICATED
 * {@link PeerIdentity#transportPeerKey()} — NEVER by an advisory {@code AgentHello.deviceId} (a lying agent
 * must not be able to claim another device's slot; the verified deviceId→peer mapping is the broker's T-4
 * job, after B1.4 cert binding).
 *
 * <p>One CONTROL stream per authenticated peer, held as a {@link ControlStreamHandle} (the serialized write
 * path — Codex P2: heartbeat/kill/replace must never write a raw {@code StreamObserver} concurrently). A
 * reconnect REPLACES the previous stream: the old handle is closed (its heartbeat task cancelled with it) —
 * only for the SAME authenticated peer. {@link #killPeer} is THE sub-second kill path: it pushes
 * {@code Envelope.kill} onto CONTROL and terminates the stream — CONTROL is a separate HTTP/2 stream from
 * DATA, so DATA backpressure can never queue ahead of it (tested).
 */
public final class ControlStreamRegistry {

    private final Map<String, ControlStreamHandle> streams = new ConcurrentHashMap<>();

    /** Register the peer's CONTROL handle; an existing handle for the SAME peer is closed and replaced. */
    void register(PeerIdentity peer, ControlStreamHandle handle) {
        ControlStreamHandle previous = streams.put(peer.transportPeerKey(), handle);
        if (previous != null && previous != handle) {
            previous.close();
        }
    }

    /** Remove the peer's handle — only if it is still THIS handle (a replaced stream must not unregister its successor). */
    void unregister(PeerIdentity peer, ControlStreamHandle handle) {
        streams.remove(peer.transportPeerKey(), handle);
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
     * KILL the authenticated peer's session NOW: push {@code Envelope.kill} on CONTROL, then terminate and
     * unregister the stream (terminal — Codex T-2b guidance). Returns false when the peer has no live
     * CONTROL stream. A dead stream still ends removed (the safe outcome; the T-3 agent's heartbeat-loss
     * handling is the backstop).
     *
     * @param sessionId the broker session this kill targets, or null/blank for a peer-scoped transport kill
     *                  ({@link #TRANSPORT_KILL_SESSION})
     */
    public boolean killPeer(String transportPeerKey, String sessionId, String killReason, long nowEpochMillis) {
        ControlStreamHandle handle = streams.remove(transportPeerKey);
        if (handle == null) {
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
        handle.sendAndClose(kill);
        return true;
    }

    /**
     * Faz 22.6 T-4a-ii slice-4a — push a signed {@link OperationPermit} to the authenticated peer's live
     * CONTROL stream (the broker permitted the operation). Unlike {@link #killPeer}, this is NON-terminal:
     * the stream is fetched with {@code get} (not removed) and stays open — the session continues. Returns
     * false when the peer has no live CONTROL stream (a dropped peer cannot receive a permit; the operation
     * simply does not proceed — fail-closed, no permit lands). The broker's record-before-permit rule has
     * already run UPSTREAM, so a permit reaching here is durably recorded; this method only transports it.
     */
    public boolean sendOperationPermit(String transportPeerKey, OperationPermit permit, long nowEpochMillis) {
        if (permit == null) {
            return false;
        }
        ControlStreamHandle handle = streams.get(transportPeerKey);
        if (handle == null) {
            return false;
        }
        Envelope envelope = Envelope.newBuilder()
                .setChannelType(ChannelType.CONTROL)
                .setSessionId(permit.sessionId())
                .setSentAtEpochMillis(nowEpochMillis)
                .setOperationPermit(RemoteBridgeProtoAdapter.encode(permit))
                .build();
        return handle.send(envelope);
    }

    /**
     * Faz 22.6 T-4a-ii slice-4a — push a {@link RemoteBridgeMessages.ConsentPrompt} to the authenticated
     * peer's live CONTROL stream (the operator opened an attended session; the agent must obtain the
     * end-user's consent before any operation). NON-terminal {@code get} — the stream stays open awaiting the
     * consent result. Returns false when the peer has no live CONTROL stream. No authority is conferred by the
     * prompt itself; the operator's permits remain gated on the consent LEASE the agent reports back.
     */
    public boolean sendConsentPrompt(String transportPeerKey, RemoteBridgeMessages.ConsentPrompt prompt,
                                     long nowEpochMillis) {
        if (prompt == null) {
            return false;
        }
        ControlStreamHandle handle = streams.get(transportPeerKey);
        if (handle == null) {
            return false;
        }
        Envelope envelope = Envelope.newBuilder()
                .setChannelType(ChannelType.CONTROL)
                .setSessionId(prompt.sessionId())
                .setSentAtEpochMillis(nowEpochMillis)
                .setConsentPrompt(RemoteBridgeProtoAdapter.encode(prompt))
                .build();
        return handle.send(envelope);
    }

    /** Close every live stream (server shutdown) — each handle cancels its own heartbeat task. */
    public void completeAll() {
        streams.values().forEach(ControlStreamHandle::close);
        streams.clear();
    }
}
