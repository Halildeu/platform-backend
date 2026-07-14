package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.contract.OperationPermit;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.proto.ChannelType;
import com.example.endpointadmin.remoteaccess.bridge.proto.Envelope;
import com.example.endpointadmin.remoteaccess.bridge.proto.Kill;
import com.example.endpointadmin.remoteaccess.bridge.wire.RemoteBridgeProtoAdapter;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

    private static final int PEER_LOCK_STRIPES = 256;
    private static final int MAX_HEARTBEAT_FAULT_OBSERVATIONS = 1_024;
    private static final long FAULT_OBSERVATION_RETENTION_MILLIS = 180_000L;

    /** One live CONTROL stream: the authenticated peer + its serialized write handle, kept atomically together. */
    private record ConnectedPeer(PeerIdentity peer, ControlStreamHandle handle) {
    }

    /** Public, redacted correlation ticket for one bounded heartbeat-loss acceptance probe. */
    public record HeartbeatSuppressionTicket(String probeId, long suppressedUntilEpochMillis,
                                             boolean newlyArmed) {
    }

    public enum HeartbeatFaultStatus {
        ARMED,
        CONTROL_STREAM_CLOSED,
        CANCELLED
    }

    private static final class HeartbeatFaultObservation {
        private final String transportPeerKey;
        private final ControlStreamHandle handle;
        private final long suppressedUntilEpochMillis;
        private volatile HeartbeatFaultStatus status = HeartbeatFaultStatus.ARMED;

        private HeartbeatFaultObservation(String transportPeerKey,
                                          ControlStreamHandle handle,
                                          long suppressedUntilEpochMillis) {
            this.transportPeerKey = transportPeerKey;
            this.handle = handle;
            this.suppressedUntilEpochMillis = suppressedUntilEpochMillis;
        }
    }

    private final Map<String, ConnectedPeer> streams = new ConcurrentHashMap<>();
    private final Map<String, HeartbeatFaultObservation> heartbeatFaults = new ConcurrentHashMap<>();
    private final Object heartbeatFaultObservationLock = new Object();
    private final Object[] peerLocks = createPeerLocks();

    /** Register the peer's CONTROL handle; an existing handle for the SAME peer is closed and replaced. */
    void register(PeerIdentity peer, ControlStreamHandle handle) {
        synchronized (peerLock(peer.transportPeerKey())) {
            ConnectedPeer previous = streams.put(peer.transportPeerKey(), new ConnectedPeer(peer, handle));
            if (previous != null && previous.handle() != handle) {
                previous.handle().close();
            }
        }
    }

    /** Remove the peer's handle — only if it is still THIS handle (a replaced stream must not unregister its successor). */
    boolean unregister(PeerIdentity peer, ControlStreamHandle handle) {
        return unregister(peer, handle, null);
    }

    /**
     * Remove the current handle and run its transport-loss action before a successor can register for the same
     * peer key. The callback executes under an explicit re-entrant peer stripe (but outside the stream handle and
     * ConcurrentHashMap internal locks), closing the remove→reconnect race without running broker/viewer/audit
     * work inside a CHM compute callback. Same-key registry re-entry is therefore defined and deadlock-free.
     */
    boolean unregister(PeerIdentity peer, ControlStreamHandle handle, Runnable onRemoved) {
        String peerKey = peer.transportPeerKey();
        synchronized (peerLock(peerKey)) {
            ConnectedPeer entry = streams.get(peerKey);
            boolean heartbeatFaultClosed = markHeartbeatFaultClosed(peerKey, handle);
            // A normal replaced stream cannot remove or report loss for its successor. A heartbeat-fault-armed
            // stream is different: the agent watchdog may reconnect before the old inbound callback arrives.
            // Its exact armed handle is still real loss evidence, so run terminal cleanup once while preserving
            // the successor transport for a future session.
            if (entry == null || entry.handle() != handle) {
                if (heartbeatFaultClosed && onRemoved != null
                        && handle.claimHeartbeatFaultTerminalCleanup()) {
                    onRemoved.run();
                }
                return false;
            }
            streams.remove(peerKey, entry);
            // Removal is already committed before policy cleanup. Holding the same peer stripe prevents a
            // successor from registering until cleanup finishes, while a callback failure cannot restore the slot.
            if (onRemoved != null) {
                onRemoved.run();
            }
            return true;
        }
    }

    /**
     * Suppress only broker→agent heartbeat frames for one authenticated peer. General CONTROL traffic remains
     * live. Repeating the call while already armed is idempotent and does not extend the original deadline.
     */
    public Optional<HeartbeatSuppressionTicket> suppressHeartbeats(String transportPeerKey,
                                                                   String requestedProbeId,
                                                                   long nowEpochMillis,
                                                                   long untilEpochMillis) {
        if (transportPeerKey == null || transportPeerKey.isBlank()
                || requestedProbeId == null || requestedProbeId.isBlank()
                || nowEpochMillis < 0L
                || untilEpochMillis <= nowEpochMillis) {
            return Optional.empty();
        }
        synchronized (peerLock(transportPeerKey)) {
            cleanupExpiredHeartbeatFaults(nowEpochMillis);
            ConnectedPeer entry = streams.get(transportPeerKey);
            if (entry == null) {
                return Optional.empty();
            }
            ControlStreamHandle.HeartbeatSuppression armed = entry.handle().suppressHeartbeats(
                    requestedProbeId, nowEpochMillis, untilEpochMillis);
            if (armed == null) {
                return Optional.empty();
            }
            synchronized (heartbeatFaultObservationLock) {
                if (armed.newlyArmed() && heartbeatFaults.size() >= MAX_HEARTBEAT_FAULT_OBSERVATIONS) {
                    entry.handle().cancelHeartbeatSuppression();
                    return Optional.empty();
                }
                heartbeatFaults.computeIfAbsent(armed.probeId(), ignored -> new HeartbeatFaultObservation(
                        transportPeerKey, entry.handle(), armed.untilEpochMillis()));
            }
            return Optional.of(new HeartbeatSuppressionTicket(
                    armed.probeId(), armed.untilEpochMillis(), armed.newlyArmed()));
        }
    }

    /** True only when the exact fault-armed CONTROL handle actually closed. */
    public boolean heartbeatFaultControlStreamClosed(String probeId) {
        return heartbeatFaultStatus(probeId).orElse(null) == HeartbeatFaultStatus.CONTROL_STREAM_CLOSED;
    }

    public Optional<HeartbeatFaultStatus> heartbeatFaultStatus(String probeId) {
        if (probeId == null) {
            return Optional.empty();
        }
        HeartbeatFaultObservation observation = heartbeatFaults.get(probeId);
        return observation == null ? Optional.empty() : Optional.of(observation.status);
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
     * Resolve a live peer by the AD CS machine certificate's {@code adcomputer:{objectGUID}} SAN binding.
     * This is a fallback for certificate renewal / DB thumbprint drift cases only; exact transport thumbprint
     * lookup remains the primary path. The lookup is fail-closed: invalid GUID input, missing SAN binding, or a
     * dropped stream all return empty.
     */
    public Optional<PeerIdentity> connectedPeerByAdComputerId(String adComputerId) {
        String wanted = canonicalAdComputerId(adComputerId);
        if (wanted == null) {
            return Optional.empty();
        }
        return streams.values().stream()
                .map(ConnectedPeer::peer)
                .filter(peer -> peer.certBoundAdComputerId().filter(wanted::equals).isPresent())
                .findFirst();
    }

    private static String canonicalAdComputerId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim()).toString();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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
        ConnectedPeer entry;
        synchronized (peerLock(transportPeerKey)) {
            entry = streams.remove(transportPeerKey);
            if (entry != null) {
                cancelHeartbeatFault(entry.handle());
            }
        }
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
        return entry.handle().sendAndClose(kill);
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

    /**
     * Faz 22.6 #548 step-5b — push a broker-nonced {@link RemoteBridgeMessages.DeviceKeyChallenge} to the
     * authenticated peer's live CONTROL stream (the canonical device-key session attestation challenge-response).
     * The envelope carries the broker {@code sessionId} so the agent echoes it on the CONTROL channel and its
     * response correlates to THIS session. NON-terminal {@code get} — the stream stays open awaiting the response.
     * Returns false on a null/blank-session challenge OR when the peer has no live CONTROL stream (fail-closed —
     * no challenge lands, so the {@code DEVICE_KEY_ATTESTATION_REAL} verifier denies for lack of fresh evidence).
     * Issuing the challenge confers NO trust; the verifier re-derives every fact from the response.
     */
    public boolean sendDeviceKeyChallenge(String transportPeerKey, String sessionId,
                                          RemoteBridgeMessages.DeviceKeyChallenge challenge, long nowEpochMillis) {
        if (challenge == null || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        ConnectedPeer entry = streams.get(transportPeerKey);
        if (entry == null) {
            return false;
        }
        Envelope envelope = Envelope.newBuilder()
                .setChannelType(ChannelType.CONTROL)
                .setSessionId(sessionId)
                .setSentAtEpochMillis(nowEpochMillis)
                .setDeviceKeyChallenge(RemoteBridgeProtoAdapter.encode(challenge))
                .build();
        return entry.handle().send(envelope);
    }

    /** Close every live stream (server shutdown) — each handle cancels its own heartbeat task. */
    public void completeAll() {
        streams.values().forEach(entry -> {
            cancelHeartbeatFault(entry.handle());
            entry.handle().close();
        });
        streams.clear();
        synchronized (heartbeatFaultObservationLock) {
            heartbeatFaults.clear();
        }
    }

    private boolean markHeartbeatFaultClosed(String transportPeerKey, ControlStreamHandle handle) {
        String probeId = handle.heartbeatSuppressionProbeId();
        if (probeId == null) {
            return false;
        }
        synchronized (heartbeatFaultObservationLock) {
            HeartbeatFaultObservation observation = heartbeatFaults.get(probeId);
            if (observation == null || observation.handle != handle
                    || !observation.transportPeerKey.equals(transportPeerKey)
                    || observation.status != HeartbeatFaultStatus.ARMED) {
                return false;
            }
            observation.status = HeartbeatFaultStatus.CONTROL_STREAM_CLOSED;
            return true;
        }
    }

    private void cancelHeartbeatFault(ControlStreamHandle handle) {
        String probeId = handle.cancelHeartbeatSuppression();
        if (probeId != null) {
            synchronized (heartbeatFaultObservationLock) {
                HeartbeatFaultObservation observation = heartbeatFaults.get(probeId);
                if (observation != null && observation.handle == handle
                        && observation.status == HeartbeatFaultStatus.ARMED) {
                    observation.status = HeartbeatFaultStatus.CANCELLED;
                }
            }
        }
    }

    /** Opportunistic bounded cleanup; the hard 1024-entry cap still refuses new unique probes fail-closed. */
    private void cleanupExpiredHeartbeatFaults(long nowEpochMillis) {
        synchronized (heartbeatFaultObservationLock) {
            heartbeatFaults.entrySet().removeIf(entry -> nowEpochMillis > entry.getValue().suppressedUntilEpochMillis
                    && nowEpochMillis - entry.getValue().suppressedUntilEpochMillis
                    > FAULT_OBSERVATION_RETENTION_MILLIS);
        }
    }

    private Object peerLock(String transportPeerKey) {
        int hash = transportPeerKey.hashCode();
        hash ^= hash >>> 16;
        return peerLocks[hash & (PEER_LOCK_STRIPES - 1)];
    }

    private static Object[] createPeerLocks() {
        Object[] locks = new Object[PEER_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }
}
