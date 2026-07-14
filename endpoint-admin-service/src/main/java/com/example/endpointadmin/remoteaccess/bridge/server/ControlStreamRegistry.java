package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.contract.OperationPermit;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeAuditSink;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.proto.ChannelType;
import com.example.endpointadmin.remoteaccess.bridge.proto.Envelope;
import com.example.endpointadmin.remoteaccess.bridge.proto.Kill;
import com.example.endpointadmin.remoteaccess.bridge.wire.RemoteBridgeProtoAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

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

    private static final Logger log = LoggerFactory.getLogger(ControlStreamRegistry.class);
    private static final int PEER_LOCK_STRIPES = 256;
    private static final int MAX_HEARTBEAT_FAULT_OBSERVATIONS = 1_024;
    private static final long FAULT_OBSERVATION_RETENTION_MILLIS = 180_000L;
    public static final String EVENT_AGENT_KILL_APPLIED = "AGENT_KILL_APPLIED";

    public enum OperatorKillAckResult {
        ACKNOWLEDGED,
        ACKNOWLEDGED_AUDIT_FAILED,
        REFUSED_NO_PENDING,
        REFUSED_WRONG_SESSION,
        REFUSED_INVALID_PROVENANCE,
        REFUSED_OUTSIDE_FRESHNESS_WINDOW,
        REFUSED_HANDLE_MISMATCH,
        REFUSED_EXPIRED
    }

    private enum PendingKillTerminal {
        ACKNOWLEDGED,
        TIMEOUT,
        SEND_FAILED,
        SCHEDULER_UNAVAILABLE,
        STREAM_CLOSED,
        STREAM_REPLACED,
        SERVER_SHUTDOWN
    }

    private record KillAckRuntime(ScheduledExecutorService scheduler,
                                  RemoteBridgeAuditSink auditSink,
                                  LongSupplier clock,
                                  long timeoutMillis,
                                  long futureClockSkewMillis) {
    }

    private static final class PendingOperatorKill {
        private final String transportPeerKey;
        private final String sessionId;
        private final ControlStreamHandle handle;
        private final long issuedAtEpochMillis;
        private final long deadlineEpochMillis;
        private volatile ScheduledFuture<?> timeoutTask;

        private PendingOperatorKill(String transportPeerKey,
                                    String sessionId,
                                    ControlStreamHandle handle,
                                    long issuedAtEpochMillis,
                                    long deadlineEpochMillis) {
            this.transportPeerKey = transportPeerKey;
            this.sessionId = sessionId;
            this.handle = handle;
            this.issuedAtEpochMillis = issuedAtEpochMillis;
            this.deadlineEpochMillis = deadlineEpochMillis;
        }
    }

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
    private final Map<String, PendingOperatorKill> pendingOperatorKills = new ConcurrentHashMap<>();
    private final Object heartbeatFaultObservationLock = new Object();
    private final Object[] peerLocks = createPeerLocks();
    private final KillAckRuntime killAckRuntime;
    private final AtomicBoolean operatorKillAckAuditFailureLatched = new AtomicBoolean();

    /** Back-compatible unit-test/inert wiring: operator close keeps the original immediate terminal path. */
    public ControlStreamRegistry() {
        this.killAckRuntime = null;
    }

    ControlStreamRegistry(ScheduledExecutorService scheduler,
                          RemoteBridgeAuditSink auditSink,
                          LongSupplier clock,
                          long timeoutMillis,
                          long futureClockSkewMillis) {
        if (scheduler == null || auditSink == null || clock == null) {
            throw new IllegalArgumentException("kill ACK scheduler, audit sink and clock are required");
        }
        if (timeoutMillis <= 0L || futureClockSkewMillis < 0L) {
            throw new IllegalArgumentException("kill ACK timeout must be positive and clock skew non-negative");
        }
        this.killAckRuntime = new KillAckRuntime(
                scheduler, auditSink, clock, timeoutMillis, futureClockSkewMillis);
    }

    /** Register the peer's CONTROL handle; an existing handle for the SAME peer is closed and replaced. */
    void register(PeerIdentity peer, ControlStreamHandle handle) {
        ConnectedPeer previous;
        PendingOperatorKill replacedPending = null;
        synchronized (peerLock(peer.transportPeerKey())) {
            previous = streams.put(peer.transportPeerKey(), new ConnectedPeer(peer, handle));
            if (previous != null && previous.handle() != handle) {
                replacedPending = removePendingForHandle(peer.transportPeerKey(), previous.handle());
            }
        }
        finishPending(replacedPending, PendingKillTerminal.STREAM_REPLACED, now());
        if (previous != null && previous.handle() != handle) {
            previous.handle().close();
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
        PendingOperatorKill closedPending;
        RuntimeException callbackFailure = null;
        boolean removed;
        synchronized (peerLock(peerKey)) {
            ConnectedPeer entry = streams.get(peerKey);
            boolean heartbeatFaultClosed = markHeartbeatFaultClosed(peerKey, handle);
            closedPending = removePendingForHandle(peerKey, handle);
            // A normal replaced stream cannot remove or report loss for its successor. A heartbeat-fault-armed
            // stream is different: the agent watchdog may reconnect before the old inbound callback arrives.
            // Its exact armed handle is still real loss evidence, so run terminal cleanup once while preserving
            // the successor transport for a future session.
            if (entry == null || entry.handle() != handle) {
                if (heartbeatFaultClosed && onRemoved != null
                        && handle.claimHeartbeatFaultTerminalCleanup()) {
                    try {
                        onRemoved.run();
                    } catch (RuntimeException failure) {
                        callbackFailure = failure;
                    }
                }
                removed = false;
            } else {
                streams.remove(peerKey, entry);
                removed = true;
                // Removal is already committed before policy cleanup. Holding the same peer stripe prevents a
                // successor from registering until cleanup finishes, while a callback failure cannot restore the slot.
                if (onRemoved != null) {
                    try {
                        onRemoved.run();
                    } catch (RuntimeException failure) {
                        callbackFailure = failure;
                    }
                }
            }
        }
        finishPending(closedPending, PendingKillTerminal.STREAM_CLOSED, now());
        if (callbackFailure != null) {
            throw callbackFailure;
        }
        return removed;
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
        ConnectedPeer entry = streams.get(transportPeerKey);
        return entry != null && entry.handle().isApplicationControlAvailable();
    }

    public int connectedCount() {
        return streams.size();
    }

    /** Runtime/metrics acceptance guard: once a durable ACK outcome write fails, this pod never reports clean. */
    public boolean operatorKillAckAuditFailureLatched() {
        return operatorKillAckAuditFailureLatched.get();
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
        return entry == null || !entry.handle().isApplicationControlAvailable()
                ? Optional.empty()
                : Optional.of(entry.peer());
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
                .filter(entry -> entry.handle().isApplicationControlAvailable())
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
        PendingOperatorKill interruptedPending = null;
        synchronized (peerLock(transportPeerKey)) {
            entry = streams.remove(transportPeerKey);
            if (entry != null) {
                cancelHeartbeatFault(entry.handle());
                interruptedPending = removePendingForHandle(transportPeerKey, entry.handle());
            }
        }
        if (entry == null) {
            return false;
        }
        String session = sessionId == null || sessionId.isBlank() ? TRANSPORT_KILL_SESSION : sessionId;
        boolean delivered = entry.handle().sendAndClose(killEnvelope(session, killReason, nowEpochMillis));
        // Emergency transport termination is never downstream of durable I/O. The ACK correlation was already
        // detached under the peer lock; record its terminal reason only after the safety KILL and close completed.
        finishPending(interruptedPending, PendingKillTerminal.STREAM_CLOSED, nowEpochMillis);
        return delivered;
    }

    /**
     * Send an operator-close KILL but keep this exact authenticated CONTROL generation open for a bounded,
     * session-bound {@code AGENT_KILL_APPLIED}. The ordinary {@link #killPeer} path remains immediate for duress,
     * transport and policy emergencies. When the ACK runtime is not wired (legacy unit seams), this safely falls
     * back to the original immediate terminal path and cannot produce an ACK marker.
     */
    public boolean killPeerAwaitingOperatorAck(String transportPeerKey, String sessionId, long nowEpochMillis) {
        KillAckRuntime runtime = killAckRuntime;
        if (runtime == null) {
            return killPeer(transportPeerKey, sessionId, "OPERATOR_CLOSE", nowEpochMillis);
        }
        if (transportPeerKey == null || transportPeerKey.isBlank()
                || sessionId == null || sessionId.isBlank() || nowEpochMillis < 0L) {
            return false;
        }

        PendingOperatorKill pending;
        ControlStreamHandle fallbackHandle = null;
        long dispatchEpochMillis = runtime.clock().getAsLong();
        synchronized (peerLock(transportPeerKey)) {
            ConnectedPeer entry = streams.get(transportPeerKey);
            if (entry == null || pendingOperatorKills.containsKey(transportPeerKey)
                    || !entry.handle().quarantineForOperatorKill()) {
                return false;
            }
            cancelHeartbeatFault(entry.handle());
            long deadline = saturatedAdd(dispatchEpochMillis, runtime.timeoutMillis());
            pending = new PendingOperatorKill(
                    transportPeerKey, sessionId, entry.handle(), dispatchEpochMillis, deadline);
            pendingOperatorKills.put(transportPeerKey, pending);
            try {
                pending.timeoutTask = runtime.scheduler().schedule(
                        () -> timeoutOperatorKill(pending), runtime.timeoutMillis(), TimeUnit.MILLISECONDS);
            } catch (RuntimeException schedulingFailure) {
                pendingOperatorKills.remove(transportPeerKey, pending);
                streams.remove(transportPeerKey, entry);
                fallbackHandle = entry.handle();
            }
        }
        if (fallbackHandle != null) {
            finishPending(pending, PendingKillTerminal.SCHEDULER_UNAVAILABLE, dispatchEpochMillis);
            return fallbackHandle.sendAndClose(killEnvelope(sessionId, "OPERATOR_CLOSE", dispatchEpochMillis));
        }
        boolean delivered = pending.handle.send(killEnvelope(sessionId, "OPERATOR_CLOSE", dispatchEpochMillis));
        if (!delivered) {
            boolean claimed;
            synchronized (peerLock(transportPeerKey)) {
                claimed = pendingOperatorKills.remove(transportPeerKey, pending);
                ConnectedPeer current = streams.get(transportPeerKey);
                if (claimed && current != null && current.handle() == pending.handle) {
                    streams.remove(transportPeerKey, current);
                }
            }
            if (claimed) {
                finishPending(pending, PendingKillTerminal.SEND_FAILED, now());
            }
        }
        return delivered;
    }

    /** Accept only the exact pending KILL's peer, session, handle generation, freshness and canonical hash. */
    public OperatorKillAckResult acceptOperatorKillAcknowledgement(
            PeerIdentity peer, RemoteBridgeMessages.AuditEvent event, long nowEpochMillis) {
        if (peer == null || event == null || !EVENT_AGENT_KILL_APPLIED.equals(event.eventType())) {
            return OperatorKillAckResult.REFUSED_INVALID_PROVENANCE;
        }
        String peerKey = peer.transportPeerKey();
        PendingOperatorKill pending;
        OperatorKillAckResult refusal = null;
        PendingKillTerminal terminal = null;
        synchronized (peerLock(peerKey)) {
            pending = pendingOperatorKills.get(peerKey);
            if (pending == null) {
                return OperatorKillAckResult.REFUSED_NO_PENDING;
            }
            if (!pending.sessionId.equals(event.sessionId())) {
                return OperatorKillAckResult.REFUSED_WRONG_SESSION;
            }
            ConnectedPeer current = streams.get(peerKey);
            if (current == null || current.handle() != pending.handle) {
                pendingOperatorKills.remove(peerKey, pending);
                refusal = OperatorKillAckResult.REFUSED_HANDLE_MISMATCH;
                terminal = PendingKillTerminal.STREAM_REPLACED;
            } else if (nowEpochMillis > pending.deadlineEpochMillis) {
                pendingOperatorKills.remove(peerKey, pending);
                streams.remove(peerKey, current);
                refusal = OperatorKillAckResult.REFUSED_EXPIRED;
                terminal = PendingKillTerminal.TIMEOUT;
            } else if (event.epochMillis() < pending.issuedAtEpochMillis
                    || event.epochMillis() > saturatedAdd(nowEpochMillis, killAckRuntime.futureClockSkewMillis())) {
                return OperatorKillAckResult.REFUSED_OUTSIDE_FRESHNESS_WINDOW;
            } else if (!canonicalAgentAckHash(event).equals(event.contentHash())) {
                return OperatorKillAckResult.REFUSED_INVALID_PROVENANCE;
            } else {
                pendingOperatorKills.remove(peerKey, pending);
                streams.remove(peerKey, current);
                cancelHeartbeatFault(pending.handle);
                terminal = PendingKillTerminal.ACKNOWLEDGED;
            }
        }

        boolean auditRecorded = finishPending(pending, terminal, nowEpochMillis);
        pending.handle.close();
        if (refusal != null) {
            return refusal;
        }
        return auditRecorded
                ? OperatorKillAckResult.ACKNOWLEDGED
                : OperatorKillAckResult.ACKNOWLEDGED_AUDIT_FAILED;
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
        return entry.handle().sendApplicationControl(envelope);
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
        return entry.handle().sendApplicationControl(envelope);
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
        return entry.handle().sendApplicationControl(envelope);
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
        return entry.handle().sendApplicationControl(envelope);
    }

    /** Close every live stream (server shutdown) — each handle cancels its own heartbeat task. */
    public void completeAll() {
        pendingOperatorKills.forEach((peerKey, pending) -> {
            if (pendingOperatorKills.remove(peerKey, pending)) {
                finishPending(pending, PendingKillTerminal.SERVER_SHUTDOWN, now());
            }
        });
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

    private void timeoutOperatorKill(PendingOperatorKill pending) {
        boolean close = false;
        long now = now();
        synchronized (peerLock(pending.transportPeerKey)) {
            if (!pendingOperatorKills.remove(pending.transportPeerKey, pending)) {
                return;
            }
            ConnectedPeer current = streams.get(pending.transportPeerKey);
            if (current != null && current.handle() == pending.handle) {
                streams.remove(pending.transportPeerKey, current);
                close = true;
            }
        }
        finishPending(pending, PendingKillTerminal.TIMEOUT, now);
        if (close) {
            pending.handle.close();
        }
    }

    private PendingOperatorKill removePendingForHandle(String transportPeerKey, ControlStreamHandle handle) {
        PendingOperatorKill pending = pendingOperatorKills.get(transportPeerKey);
        if (pending != null && pending.handle == handle
                && pendingOperatorKills.remove(transportPeerKey, pending)) {
            return pending;
        }
        return null;
    }

    private boolean finishPending(PendingOperatorKill pending, PendingKillTerminal terminal, long nowEpochMillis) {
        if (pending == null || terminal == null) {
            return false;
        }
        ScheduledFuture<?> timeoutTask = pending.timeoutTask;
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
        }
        KillAckRuntime runtime = killAckRuntime;
        if (runtime == null) {
            return false;
        }
        String eventType = terminal == PendingKillTerminal.ACKNOWLEDGED
                ? "SESSION_CLOSE:AGENT_KILL_APPLIED"
                : "SESSION_CLOSE:AGENT_KILL_ACK_" + terminal.name();
        try {
            runtime.auditSink().record(new RemoteBridgeMessages.AuditEvent(
                    pending.sessionId, eventType, sha256Hex(eventType), nowEpochMillis));
            return true;
        } catch (RuntimeException auditFailure) {
            operatorKillAckAuditFailureLatched.set(true);
            log.error("remote-bridge operator KILL ACK audit failed; terminal={} session remains closed", terminal);
            return false;
        }
    }

    private static Envelope killEnvelope(String sessionId, String killReason, long nowEpochMillis) {
        return Envelope.newBuilder()
                .setChannelType(ChannelType.CONTROL)
                .setSessionId(sessionId)
                .setSentAtEpochMillis(nowEpochMillis)
                .setKill(Kill.newBuilder()
                        .setSessionId(sessionId)
                        .setKillReason(killReason == null || killReason.isBlank() ? "killed" : killReason)
                        .setIssuedAtEpochMillis(nowEpochMillis))
                .build();
    }

    private static String canonicalAgentAckHash(RemoteBridgeMessages.AuditEvent event) {
        return sha256Hex(event.sessionId() + "\n" + event.eventType() + "\n" + event.epochMillis());
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static long saturatedAdd(long value, long increment) {
        if (increment > 0L && value > Long.MAX_VALUE - increment) {
            return Long.MAX_VALUE;
        }
        return value + increment;
    }

    private long now() {
        KillAckRuntime runtime = killAckRuntime;
        return runtime == null ? System.currentTimeMillis() : runtime.clock().getAsLong();
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
