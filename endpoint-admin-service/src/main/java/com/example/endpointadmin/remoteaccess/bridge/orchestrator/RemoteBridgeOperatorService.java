package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeBroker;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeBroker.BrokerOutcome;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.Event;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeTrustEvidence;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.OperationRequest;
import com.example.endpointadmin.remoteaccess.bridge.server.ControlStreamRegistry;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Faz 22.6 T-4a-ii slice-4b-2 (Codex 019ebd7f) — the operator-side orchestration: drives one operator
 * {@link OperationRequest} through the broker and routes the {@link BrokerOutcome} to the transport. This is
 * the seam that finally connects the broker (slice-3c) to the agent push primitives (slice-4a). Issues NO
 * authority itself — the broker is the only thing that mints a permit; this service only transports the
 * verdict. Disabled-by-default (the bean exists only when {@code remote-bridge.enabled=true}).
 *
 * <p><b>Pipeline:</b> a malformed request or an unknown session is REJECTED before the broker is consulted (no
 * {@code nextSeq} burned — Codex S2). Otherwise the {@link TrustEvidenceAssembler} builds the fail-closed
 * evidence, {@code broker.handle} decides, and the outcome routes:
 * <ul>
 *   <li><b>PERMIT</b> → push the signed permit on the peer's CONTROL stream; {@code transportPushed=false}
 *       when the stream is gone (the permit was durably recorded upstream, but it simply does not land — the
 *       operation does not proceed, no further action — Codex S3).</li>
 *   <li><b>KILL</b> → kill the transport stream AND drive the session state machine to a terminal state +
 *       evict it (so no ACTIVE ghost session lingers — Codex S1). A duress that is AMBIGUOUS because the
 *       transport duress-classification is not yet wired surfaces here as a KILL — fail-closed.</li>
 *   <li><b>DENY</b> → nothing pushed; the broker already recorded the denial.</li>
 * </ul>
 */
public final class RemoteBridgeOperatorService {

    /** The operator-facing outcome: the broker verdict (null when rejected pre-broker) + whether it transported. */
    public record OperatorOutcome(BrokerOutcome brokerOutcome, boolean transportPushed, String rejectReason) {
        public boolean accepted() {
            return brokerOutcome != null;
        }

        static OperatorOutcome handled(BrokerOutcome outcome, boolean transportPushed) {
            return new OperatorOutcome(outcome, transportPushed, null);
        }

        static OperatorOutcome rejected(String reason) {
            return new OperatorOutcome(null, false, reason);
        }
    }

    private final RemoteBridgeSessionStore store;
    private final TrustEvidenceAssembler assembler;
    private final RemoteBridgeBroker broker;
    private final ControlStreamRegistry registry;
    private final LongSupplier clock;

    public RemoteBridgeOperatorService(RemoteBridgeSessionStore store, TrustEvidenceAssembler assembler,
                                       RemoteBridgeBroker broker, ControlStreamRegistry registry,
                                       LongSupplier clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.broker = Objects.requireNonNull(broker, "broker");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public OperatorOutcome handleOperationRequest(OperationRequest request) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            return OperatorOutcome.rejected("malformed-request");
        }
        long now = clock.getAsLong();
        RemoteBridgeSession session = store.bySessionId(request.sessionId()).orElse(null);
        if (session == null) {
            return OperatorOutcome.rejected("unknown-session"); // no broker call, no seq burned (Codex S2)
        }

        RemoteBridgeTrustEvidence evidence = assembler.assemble(session, now);
        // nextSeq() consumed ONLY here, at the broker boundary, after the session is found (Codex S2)
        BrokerOutcome outcome = broker.handle(request, evidence, session.state(), session.nextSeq(), now);

        return switch (outcome.kind()) {
            case PERMIT -> {
                boolean pushed = registry.sendOperationPermit(session.transportPeerKey(), outcome.permit(), now);
                yield OperatorOutcome.handled(outcome, pushed);
            }
            case KILL -> {
                registry.killPeer(session.transportPeerKey(), request.sessionId(),
                        outcome.kill().killReason(), now);
                // Codex S1: a transport kill alone leaves an ACTIVE ghost — drive the state machine terminal + evict
                session.transition(Event.KILL);
                store.evictIfTerminal(request.sessionId());
                yield OperatorOutcome.handled(outcome, false);
            }
            case DENY -> OperatorOutcome.handled(outcome, false); // broker already recorded the denial
        };
    }
}
