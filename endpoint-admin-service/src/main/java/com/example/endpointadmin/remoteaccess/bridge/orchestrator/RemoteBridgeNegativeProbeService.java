package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgePermitSigner;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import com.example.endpointadmin.remoteaccess.bridge.contract.CanonicalCommand;
import com.example.endpointadmin.remoteaccess.bridge.contract.OperationPermit;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.OperationDispatch;
import com.example.endpointadmin.remoteaccess.bridge.server.ControlStreamRegistry;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Non-prod acceptance-only probes for agent-side negative permit handling. The service is deliberately not a
 * broad remote-exec path: it always uses the approved pilot command {@code hostname}, returns only bounded
 * metadata, and treats success as observing a real agent deny frame.
 */
public final class RemoteBridgeNegativeProbeService {

    public static final String EXPIRED_PERMIT_DENY_CODE = "operation-dispatch-failed:permit-invalid";
    public static final String REPLAY_DENY_CODE = "operation-dispatch-failed:seq-replay";

    private static final String PROBE_POLICY_VERSION = "rb-negative-probe-v1";
    private static final String PROBE_COMMAND = "hostname";

    public record ProbeOutcome(boolean observedDeny,
                               String reason,
                               boolean transportPushed,
                               String agentErrorCode,
                               String operationId,
                               long seq) {
        static ProbeOutcome rejected(String reason) {
            return new ProbeOutcome(false, reason, false, null, null, 0L);
        }
    }

    private final ControlStreamRegistry registry;
    private final RemoteBridgePermitSigner signer;
    private final RemoteBridgeAgentErrorLedger agentErrorLedger;
    private final LongSupplier clock;
    private final long permitTtlMillis;
    private final long observationTimeoutMillis;

    public RemoteBridgeNegativeProbeService(ControlStreamRegistry registry,
                                            RemoteBridgePermitSigner signer,
                                            RemoteBridgeAgentErrorLedger agentErrorLedger,
                                            LongSupplier clock,
                                            long permitTtlMillis,
                                            long observationTimeoutMillis) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.agentErrorLedger = Objects.requireNonNull(agentErrorLedger, "agentErrorLedger");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (permitTtlMillis <= 0 || observationTimeoutMillis < 0) {
            throw new IllegalArgumentException("permitTtlMillis must be positive and observationTimeoutMillis non-negative");
        }
        this.permitTtlMillis = permitTtlMillis;
        this.observationTimeoutMillis = observationTimeoutMillis;
    }

    public ProbeOutcome expiredPermit(RemoteBridgeSession session) {
        if (!isProbeable(session)) {
            return ProbeOutcome.rejected("session-not-active");
        }
        long now = clock.getAsLong();
        long ttl = Math.max(1_000L, permitTtlMillis);
        long issuedAt = now - (ttl * 2);
        long expiresAt = now - ttl;
        long seq = session.nextSeqValue();
        String operationId = "expired-permit-probe-" + UUID.randomUUID();
        Optional<OperationPermit> permit = signProbePermit(session, operationId, issuedAt, expiresAt, seq);
        if (permit.isEmpty()) {
            return ProbeOutcome.rejected("probe-permit-signing-refused");
        }
        boolean pushed = registry.sendOperationDispatch(session.transportPeerKey(),
                new OperationDispatch(permit.get(), PROBE_COMMAND), now);
        if (!pushed) {
            return new ProbeOutcome(false, "probe-dispatch-not-pushed", false, null, operationId, seq);
        }
        return waitForAgentDeny(session, operationId, seq, EXPIRED_PERMIT_DENY_CODE,
                "expired-permit-denied", now, pushed);
    }

    public ProbeOutcome replayPermit(RemoteBridgeSession session) {
        if (!isProbeable(session)) {
            return ProbeOutcome.rejected("session-not-active");
        }
        if (session.nextSeqValue() <= 1L) {
            return ProbeOutcome.rejected("replay-probe-requires-prior-operation");
        }
        long now = clock.getAsLong();
        long seq = 1L;
        String operationId = "replay-permit-probe-" + UUID.randomUUID();
        Optional<OperationPermit> permit = signProbePermit(session, operationId, now,
                now + permitTtlMillis, seq);
        if (permit.isEmpty()) {
            return ProbeOutcome.rejected("probe-permit-signing-refused");
        }
        boolean pushed = registry.sendOperationDispatch(session.transportPeerKey(),
                new OperationDispatch(permit.get(), PROBE_COMMAND), now);
        if (!pushed) {
            return new ProbeOutcome(false, "probe-dispatch-not-pushed", false, null, operationId, seq);
        }
        return waitForAgentDeny(session, operationId, seq, REPLAY_DENY_CODE,
                "replay-denied", now, pushed);
    }

    private boolean isProbeable(RemoteBridgeSession session) {
        if (session == null || session.isTerminal()) {
            return false;
        }
        State state = session.state();
        return state != null && state.isActive();
    }

    private Optional<OperationPermit> signProbePermit(RemoteBridgeSession session,
                                                      String operationId,
                                                      long issuedAt,
                                                      long expiresAt,
                                                      long seq) {
        OperationPermit unsigned = new OperationPermit(
                signer.alg(),
                signer.kid(),
                RemoteBridgePermitSigner.PERMIT_VERSION,
                PROBE_POLICY_VERSION,
                session.sessionId() + ":" + operationId,
                session.sessionId(),
                operationId,
                session.deviceId(),
                session.operatorSubject(),
                RemoteSessionCapability.CONSTRAINED_PTY,
                CanonicalCommand.of(PROBE_COMMAND).hash(),
                issuedAt,
                expiresAt,
                seq,
                null);
        return signer.sign(unsigned);
    }

    private ProbeOutcome waitForAgentDeny(RemoteBridgeSession session,
                                          String operationId,
                                          long seq,
                                          String expectedCode,
                                          String successReason,
                                          long notBeforeEpochMillis,
                                          boolean pushed) {
        long deadline = System.nanoTime() + (observationTimeoutMillis * 1_000_000L);
        do {
            Optional<RemoteBridgeAgentErrorLedger.Observation> observation = agentErrorLedger.findAfter(
                    session.sessionId(), session.transportPeerKey(), expectedCode, notBeforeEpochMillis);
            if (observation.isPresent()) {
                return new ProbeOutcome(true, successReason, pushed, observation.get().code(), operationId, seq);
            }
            if (observationTimeoutMillis == 0L || System.nanoTime() >= deadline) {
                break;
            }
            sleepQuietly(Math.min(100L, observationTimeoutMillis));
        } while (true);
        return new ProbeOutcome(false, "agent-deny-not-observed", pushed, null, operationId, seq);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
