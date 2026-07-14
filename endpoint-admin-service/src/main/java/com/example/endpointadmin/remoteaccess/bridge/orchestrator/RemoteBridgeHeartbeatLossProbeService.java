package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import com.example.endpointadmin.remoteaccess.bridge.server.ControlStreamRegistry;
import com.example.endpointadmin.remoteaccess.bridge.server.ControlStreamRegistry.HeartbeatFaultStatus;
import com.example.endpointadmin.remoteaccess.bridge.server.ControlStreamRegistry.HeartbeatSuppressionTicket;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

import static com.example.endpointadmin.remoteaccess.RemoteAccessMetrics.BRIDGE_HEARTBEAT_LOSS_PROBE_TOTAL;

/**
 * Non-prod acceptance-only heartbeat-loss probe. It suppresses only broker heartbeats for the exact mTLS peer
 * bound to one current active VIEW_ONLY session; the agent's real watchdog must then close that CONTROL stream.
 * Success requires both the exact armed handle's close observation and the broker session's real KILL terminal.
 */
public final class RemoteBridgeHeartbeatLossProbeService {

    public static final long MAX_SUPPRESSION_MILLIS = 120_000L;
    public static final long MAX_OBSERVATION_TIMEOUT_MILLIS = 180_000L;

    public record ProbeOutcome(boolean terminalObserved,
                               String reason,
                               String probeId,
                               long suppressedUntilEpochMillis,
                               String terminalState) {
        static ProbeOutcome refused(String reason) {
            return new ProbeOutcome(false, reason, null, 0L, null);
        }
    }

    private final ControlStreamRegistry registry;
    private final RemoteBridgeSessionStore sessionStore;
    private final MeterRegistry meters;
    private final LongSupplier clock;
    private final long suppressionMillis;
    private final long observationTimeoutMillis;

    public RemoteBridgeHeartbeatLossProbeService(ControlStreamRegistry registry,
                                                 RemoteBridgeSessionStore sessionStore,
                                                 MeterRegistry meters,
                                                 LongSupplier clock,
                                                 long suppressionMillis,
                                                 long observationTimeoutMillis) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.meters = Objects.requireNonNull(meters, "meters");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (suppressionMillis < 1_000L || suppressionMillis > MAX_SUPPRESSION_MILLIS) {
            throw new IllegalArgumentException("suppressionMillis must be between 1000 and 120000");
        }
        if (observationTimeoutMillis < 1_000L || observationTimeoutMillis > MAX_OBSERVATION_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("observationTimeoutMillis must be between 1000 and 180000");
        }
        this.suppressionMillis = suppressionMillis;
        this.observationTimeoutMillis = observationTimeoutMillis;
    }

    public ProbeOutcome exercise(RemoteBridgeSession session) {
        String refusal = refusalReason(session);
        if (refusal != null) {
            count(metricOutcomeForRefusal(refusal));
            return ProbeOutcome.refused(refusal);
        }

        long now = clock.getAsLong();
        if (now < 0L || now > Long.MAX_VALUE - suppressionMillis) {
            count("refused-clock");
            return ProbeOutcome.refused("invalid-clock");
        }
        Optional<HeartbeatSuppressionTicket> armed = registry.suppressHeartbeats(
                session.transportPeerKey(), UUID.randomUUID().toString(), now, now + suppressionMillis);
        if (armed.isEmpty()) {
            count("refused-peer-or-capacity");
            return ProbeOutcome.refused("peer-not-connected-or-probe-capacity");
        }

        HeartbeatSuppressionTicket ticket = armed.orElseThrow();
        long deadline = System.nanoTime() + observationTimeoutMillis * 1_000_000L;
        do {
            HeartbeatFaultStatus faultStatus = registry.heartbeatFaultStatus(ticket.probeId()).orElse(null);
            if (faultStatus == HeartbeatFaultStatus.CANCELLED) {
                count("cancelled-explicit-terminal");
                return new ProbeOutcome(false, "probe-cancelled-by-explicit-terminal", ticket.probeId(),
                        ticket.suppressedUntilEpochMillis(), session.isTerminal() ? session.state().name() : null);
            }
            if (faultStatus == HeartbeatFaultStatus.CONTROL_STREAM_CLOSED
                    && session.state() == State.KILLED) {
                count("terminal-observed");
                return new ProbeOutcome(true, "control-stream-loss-terminal-observed", ticket.probeId(),
                        ticket.suppressedUntilEpochMillis(), State.KILLED.name());
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
            if (!sleepQuietly(Math.min(100L, observationTimeoutMillis))) {
                break;
            }
        } while (true);

        count("terminal-timeout");
        return new ProbeOutcome(false, "control-stream-loss-terminal-not-observed", ticket.probeId(),
                ticket.suppressedUntilEpochMillis(), session.isTerminal() ? session.state().name() : null);
    }

    private String refusalReason(RemoteBridgeSession session) {
        if (session == null || session.isTerminal() || session.state() == null || !session.state().isActive()
                || !session.requestedCapabilities().contains(RemoteSessionCapability.VIEW_ONLY)) {
            return "session-not-active-view-only";
        }
        RemoteBridgeSession current = sessionStore.liveByPeer(session.transportPeerKey()).orElse(null);
        if (current != session) {
            return "session-not-current-peer-incarnation";
        }
        return null;
    }

    private void count(String outcome) {
        meters.counter(BRIDGE_HEARTBEAT_LOSS_PROBE_TOTAL, "outcome", outcome).increment();
    }

    private static String metricOutcomeForRefusal(String reason) {
        return switch (reason) {
            case "session-not-active-view-only" -> "refused-session";
            case "session-not-current-peer-incarnation" -> "refused-incarnation";
            default -> "refused-other";
        };
    }

    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
