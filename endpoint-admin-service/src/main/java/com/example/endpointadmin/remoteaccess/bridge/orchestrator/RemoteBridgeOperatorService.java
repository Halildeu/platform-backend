package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeAuditSink;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeBroker;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeBroker.BrokerOutcome;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.Event;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeTrustEvidence;
import com.example.endpointadmin.remoteaccess.bridge.contract.OperationPermit;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.AuditEvent;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.ConsentPrompt;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.OperationDispatch;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.OperationRequest;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.SessionRequest;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore.OpenResult;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore.Opened;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore.Refused;
import com.example.endpointadmin.remoteaccess.bridge.server.ControlStreamRegistry;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;

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
 *   <li><b>DENY</b> → nothing pushed; the broker already recorded the denial. The session is also driven
 *       terminal + evicted so a denied policy decision cannot strand the peer slot.</li>
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
    private final RemoteBridgeAuditSink auditSink;
    private final LongSupplier clock;
    private final long consentPromptTtlMillis;

    public RemoteBridgeOperatorService(RemoteBridgeSessionStore store, TrustEvidenceAssembler assembler,
                                       RemoteBridgeBroker broker, ControlStreamRegistry registry,
                                       RemoteBridgeAuditSink auditSink, LongSupplier clock,
                                       long consentPromptTtlMillis) {
        this.store = Objects.requireNonNull(store, "store");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.broker = Objects.requireNonNull(broker, "broker");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (consentPromptTtlMillis <= 0) {
            throw new IllegalArgumentException("consentPromptTtlMillis must be positive");
        }
        this.consentPromptTtlMillis = consentPromptTtlMillis;
    }

    /** The outcome of opening an attended session: the session id, whether the consent prompt reached the agent. */
    public record SessionOpenOutcome(String sessionId, boolean consentPromptSent, String rejectReason) {
        public boolean opened() {
            return rejectReason == null;
        }

        static SessionOpenOutcome prompted(String sessionId) {
            return new SessionOpenOutcome(sessionId, true, null);
        }

        static SessionOpenOutcome rejected(String sessionId, String reason) {
            return new SessionOpenOutcome(sessionId, false, reason);
        }
    }

    /** Explicit operator close outcome. A failed durable close audit refuses the close and keeps the slot live. */
    public record SessionCloseOutcome(String sessionId, boolean closed, String rejectReason) {
        public boolean accepted() {
            return closed && rejectReason == null;
        }

        static SessionCloseOutcome closed(String sessionId) {
            return new SessionCloseOutcome(sessionId, true, null);
        }

        static SessionCloseOutcome rejected(String sessionId, String reason) {
            return new SessionCloseOutcome(sessionId, false, reason);
        }
    }

    /**
     * Faz 22.6 T-4a-ii slice-4b-3 (Codex 019ebd7f) — open an ATTENDED session and push the consent prompt to
     * the agent. The store walks the new session to {@code CONSENT_PENDING} itself, so the service does NOT
     * re-drive the state machine (Codex S3). The prompt TTL is config-derived, not caller-supplied (S2).
     *
     * <p><b>Fail-closed:</b> a peer with no live CONTROL stream is refused BEFORE the session is created
     * (pre-guard). If the prompt does not land despite the pre-guard (the peer dropped in the race window), the
     * just-opened session is driven terminal + evicted so no orphan {@code CONSENT_PENDING} session lingers
     * awaiting a consent that can never arrive (Codex S3).
     *
     * <p><b>Tenant invariant (slice-4c-2b-2b, Codex 019ebe06):</b> {@code operatorTenantId} is not validated
     * here — this is a PUBLIC orchestrator seam, so the canonical-UUID invariant is enforced at the store
     * chokepoint ({@link RemoteBridgeSessionStore#open}). A blank/non-canonical tenant therefore surfaces here
     * as a {@code rejected("invalid-operator-tenant")} outcome with NO session created, even for a future caller
     * that is not the controller.
     */
    public SessionOpenOutcome openSession(SessionRequest request, PeerIdentity peer, String operatorTenantId,
                                          String operatorDisplayName) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank() || peer == null) {
            return SessionOpenOutcome.rejected(request == null ? null : request.sessionId(), "malformed-request");
        }
        long now = clock.getAsLong();
        if (!registry.isConnected(peer.transportPeerKey())) {
            return SessionOpenOutcome.rejected(request.sessionId(), "peer-not-connected"); // pre-guard
        }

        OpenResult result = store.open(request, peer, operatorTenantId, operatorDisplayName,
                now + consentPromptTtlMillis, now);
        if (result instanceof Refused refused) {
            return SessionOpenOutcome.rejected(request.sessionId(), refused.reason());
        }
        RemoteBridgeSession session = ((Opened) result).session(); // ALREADY CONSENT_PENDING (store walked it)

        ConsentPrompt prompt = new ConsentPrompt(session.sessionId(), session.operatorDisplayName(),
                request.reason(), session.requestedCapabilities(), session.promptExpiryEpochMillis());
        boolean sent = registry.sendConsentPrompt(peer.transportPeerKey(), prompt, now);
        if (!sent) {
            // the peer dropped between the pre-guard and the send → don't leave an orphan CONSENT_PENDING session
            session.transition(Event.KILL);
            store.evictIfTerminal(session.sessionId());
            return SessionOpenOutcome.rejected(session.sessionId(), "consent-prompt-not-delivered");
        }
        return SessionOpenOutcome.prompted(session.sessionId());
    }

    /**
     * Close an already ACTIVE attended session after the operator has finished the approved operation. The
     * durable session recorder is the authority boundary: if the close event cannot be recorded, the session is
     * deliberately left live so a cleanup path cannot silently bypass the audit chain.
     */
    public SessionCloseOutcome closeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return SessionCloseOutcome.rejected(sessionId, "malformed-request");
        }
        long now = clock.getAsLong();
        RemoteBridgeSession session = store.bySessionId(sessionId).orElse(null);
        if (session == null) {
            return SessionCloseOutcome.rejected(sessionId, "unknown-session");
        }
        if (session.isTerminal()) {
            store.evictIfTerminal(sessionId);
            return SessionCloseOutcome.rejected(sessionId, "session-not-live");
        }
        State state = session.state();
        if (!state.isActive() && state != State.REVOKING) {
            return SessionCloseOutcome.rejected(sessionId, "session-close-refused");
        }
        try {
            auditSink.record(new AuditEvent(session.sessionId(), "SESSION_CLOSE:OPERATOR", "", now));
        } catch (RuntimeException recordingFailure) {
            return SessionCloseOutcome.rejected(sessionId, "recording-failed");
        }
        if (!session.transition(Event.CLOSE).accepted()) {
            return SessionCloseOutcome.rejected(sessionId, "session-close-refused");
        }
        store.evictIfTerminal(sessionId);
        return SessionCloseOutcome.closed(sessionId);
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
                OperationPermit permit = outcome.permit();
                boolean pushed;
                if (permit.capability() == RemoteSessionCapability.CONSTRAINED_PTY) {
                    // CONSTRAINED_PTY carries the plaintext command alongside the signed permit
                    // (OperationDispatch). The command is bound to the SIGNED permit by the hash (the agent
                    // re-derives CanonicalCommand.hash == permit.commandHash) and is never trusted raw.
                    // Fail-closed UPSTREAM (Codex 019ecd07): a PTY permit without its command is a defect —
                    // push nothing rather than a command-less dispatch the agent would reject anyway.
                    String commandLine = request.commandLine();
                    pushed = commandLine != null && !commandLine.isBlank()
                            && registry.sendOperationDispatch(session.transportPeerKey(),
                                    new OperationDispatch(permit, commandLine), now);
                } else {
                    // VIEW_ONLY (and any non-command capability) carries no command — push the bare signed permit.
                    pushed = registry.sendOperationPermit(session.transportPeerKey(), permit, now);
                }
                // The PERMIT branch only fires under a full policy pass, exercised at the owner-gated real-PERMIT
                // e2e once the B1.4d / step-up trust roots land — forcing a PERMIT here would manufacture trust
                // the system does not have (the e2e is deliberately PERMIT-agnostic).
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
            case DENY -> {
                // Broker already recorded the denial; do not push anything to the agent. Still close the
                // operator session locally so policy DENY cannot leave an ACTIVE ghost that blocks a retry.
                session.transition(Event.CLOSE);
                store.evictIfTerminal(request.sessionId());
                yield OperatorOutcome.handled(outcome, false);
            }
        };
    }
}
