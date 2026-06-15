package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.contract.OperationPermit;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.proto.ChannelType;
import com.example.endpointadmin.remoteaccess.bridge.proto.Envelope;
import com.example.endpointadmin.remoteaccess.bridge.proto.Kill;
import com.example.endpointadmin.remoteaccess.bridge.wire.RemoteBridgeProtoAdapter;

import java.util.Map;
import java.util.Optional;
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
 *
 * <p>Faz 22.6 slice-4c-2b-2a (Codex 019ebe06) — each entry holds the {@link PeerIdentity} ALONGSIDE its handle
 * in one composite value, so {@link #connectedPeer} (the operator-side device→peer resolver's lookup) stays
 * atomically consistent with the handle on register/unregister/kill/shutdown (one map, never two that could
 * drift).
 */
public final class ControlStreamRegistry {

    /** One live CONTROL stream: the authenticated peer + its serialized write handle, kept atomically together. */
    private record ConnectedPeer(PeerIdentity peer, ControlStreamHandle handle) {
    }

    private final Map<String, ConnectedPeer> streams = new ConcurrentHashMap<>();

    /** Register the peer's CONTROL handle; an existing handle for the SAME peer is closed and replaced. */
    void register(PeerIdentity peer, ControlStreamHandle handle) {
        ConnectedPeer previous = streams.put(peer.transportPeerKey(), new ConnectedPeer(peer, handle));
        if (previous != null && previous.handle() != handle) {
            previous.handle().close();
        }
    }

    /** Remove the peer's handle — only if it is still THIS handle (a replaced stream must not unregister its successor). */
    void unregister(PeerIdentity peer, ControlStreamHandle handle) {
        // handle-identity conditional remove (atomic): drop the entry only while its handle is still this one
        streams.computeIfPresent(peer.transportPeerKey(), (key, entry) -> entry.handle() == handle ? null : entry);
    }

    public boolean isConnected(String transportPeerKey) {
        return streams.containsKey(transportPeerKey);
    }

    public int connectedCount() {
        return streams.size();
    }

    /**
     * Faz 22.6 slice-4c-2b-2a — the still-registered {@link PeerIdentity} for a transport key, or empty when no
     * live stream holds it. This is the operator-side resolver's lookup: a device's active-cert thumbprint
     * equals its {@code transportPeerKey}, so the resolver maps {@code (tenant, deviceId) → active cert
     * thumbprint → connectedPeer}. Returns the REAL registered peer (with its cert chain), only while the
     * stream is live — a dropped peer yields empty (fail-closed, no session opens to a gone agent).
     */
    public Optional<PeerIdentity> connectedPeer(String transportPeerKey) {
        if (transportPeerKey == null) {
            return Optional.empty();
        }
        ConnectedPeer entry = streams.get(transportPeerKey);
        return entry == null ? Optional.empty() : Optional.of(entry.peer());
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
        ConnectedPeer entry = streams.remove(transportPeerKey);
        if (entry == null) {
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
        entry.handle().sendAndClose(kill);
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
        ConnectedPeer entry = streams.get(transportPeerKey);
        if (entry == null) {
            return false;
        }
        Envelope envelope = Envelope.newBuilder()
                .setChannelType(ChannelType.CONTROL)
                .setSessionId(permit.sessionId())
                .setSentAtEpochMillis(nowEpochMillis)
                .setOperationPermit(RemoteBridgeProtoAdapter.encode(permit))
                .build();
        return entry.handle().send(envelope);
    }

    /**
     * Faz 22.6 T-4 — push an {@link RemoteBridgeMessages.OperationDispatch} (a signed permit paired with the
     * plaintext command) to the authenticated peer's live CONTROL stream. The CONSTRAINED_PTY counterpart of
     * {@link #sendOperationPermit}: used when the permitted operation carries a command the agent must run (the
     * permit alone carries only the one-way command hash). NON-terminal {@code get} — the session continues.
     * Returns false when the dispatch/permit is null or the peer has no live CONTROL stream (fail-closed — a
     * dropped peer cannot receive it). Record-before-permit has already run UPSTREAM; this only transports it.
     */
    public boolean sendOperationDispatch(String transportPeerKey, RemoteBridgeMessages.OperationDispatch dispatch,
                                         long nowEpochMillis) {
        if (dispatch == null || dispatch.permit() == null) {
            return false;
        }
        ConnectedPeer entry = streams.get(transportPeerKey);
        if (entry == null) {
            return false;
        }
        Envelope envelope = Envelope.newBuilder()
                .setChannelType(ChannelType.CONTROL)
                .setSessionId(dispatch.permit().sessionId())
                .setSentAtEpochMillis(nowEpochMillis)
                .setOperationDispatch(RemoteBridgeProtoAdapter.encode(dispatch))
                .build();
        return entry.handle().send(envelope);
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
        ConnectedPeer entry = streams.get(transportPeerKey);
        if (entry == null) {
            return false;
        }
        Envelope envelope = Envelope.newBuilder()
                .setChannelType(ChannelType.CONTROL)
                .setSessionId(prompt.sessionId())
                .setSentAtEpochMillis(nowEpochMillis)
                .setConsentPrompt(RemoteBridgeProtoAdapter.encode(prompt))
                .build();
        return entry.handle().send(envelope);
    }

    /** Close every live stream (server shutdown) — each handle cancels its own heartbeat task. */
    public void completeAll() {
        streams.values().forEach(entry -> entry.handle().close());
        streams.clear();
    }
}
