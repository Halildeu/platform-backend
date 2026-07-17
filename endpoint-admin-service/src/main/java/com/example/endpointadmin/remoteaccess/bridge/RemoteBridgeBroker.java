package com.example.endpointadmin.remoteaccess.bridge;

import com.example.endpointadmin.remoteaccess.RemoteOperation;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.RemoteSessionPolicyEngine;
import com.example.endpointadmin.remoteaccess.RemoteSessionPolicyEngine.Outcome;
import com.example.endpointadmin.remoteaccess.RemoteSessionPolicyEngine.SessionContext;
import com.example.endpointadmin.remoteaccess.RemoteSessionPolicyEngine.SessionDecision;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import com.example.endpointadmin.remoteaccess.bridge.contract.CanonicalCommand;
import com.example.endpointadmin.remoteaccess.bridge.contract.ConsentLease;
import com.example.endpointadmin.remoteaccess.bridge.contract.OperationPermit;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.AuditEvent;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.Kill;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.OperationRequest;
import com.example.endpointadmin.remoteaccess.bridge.contract.WireContract;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.SessionDeviceTrustVerifier.Basis;

import java.util.Optional;

/**
 * Faz 22.6 T-1b — the remote-bridge BROKER: the control-plane that turns one operator {@link OperationRequest}
 * into a signed {@link OperationPermit}, a {@link Kill}, or a deny, by composing the merged policy engine + the
 * T-1b primitives (ADR-0038, Codex 019eb9fb). Pure/total/fail-closed, config-free over the security verifiers
 * (the broker is handed a {@link RemoteBridgeTrustEvidence} the verifiers already produced). Disabled-by-default.
 *
 * <p><b>Pipeline (first failure denies):</b> disabled → deny; null/invalid request → deny; the operation↔
 * command rule (a {@code PTY_COMMAND} MUST carry a non-empty canonical command; a non-PTY operation MUST NOT
 * carry one) → deny; the session must be {@link State#isActive() ACTIVE}; the attended consent LEASE must be
 * active at {@code now}; then {@code RemoteSessionPolicyEngine.evaluate} — TERMINATE_DURESS → kill, any non-
 * ALLOW → deny. On ALLOW the broker records the decision and, ONLY IF the durable record commits, issues a
 * permit (recorder failure → deny, no permit — ADR-0034 mandatory recording). The permit is then signed; the
 * signer is the final boundary (a non-pilot / inconsistent permit it would refuse → deny).
 *
 * <p><b>Duress scope:</b> the broker decides per-OPERATION, so a duress signal here kills DURING an active
 * operation (it surfaces through the engine, which checks duress first). A duress signalled outside an active
 * operation is a session-level event the transport handles via the state machine's {@code KILL/LOCAL_ABORT}
 * (which fires from any non-terminal state) — not an operation the broker evaluates.
 */
public final class RemoteBridgeBroker {

    private static final long MAX_VIEW_ONLY_PERMIT_TTL_MILLIS = 10 * 60_000L;

    /** The broker's verdict for one operation. */
    public record BrokerOutcome(Kind kind, OperationPermit permit, Kill kill, String reason, String policyDetail) {
        public enum Kind { PERMIT, KILL, DENY }

        public BrokerOutcome(Kind kind, OperationPermit permit, Kill kill, String reason) {
            this(kind, permit, kill, reason, null);
        }

        public boolean permitted() {
            return kind == Kind.PERMIT;
        }

        static BrokerOutcome permit(OperationPermit p) {
            return new BrokerOutcome(Kind.PERMIT, p, null, "permitted", null);
        }

        static BrokerOutcome kill(Kill k) {
            return new BrokerOutcome(Kind.KILL, null, k, "duress", null);
        }

        static BrokerOutcome deny(String reason) {
            return deny(reason, null);
        }

        static BrokerOutcome deny(String reason, String policyDetail) {
            return new BrokerOutcome(Kind.DENY, null, null, reason, policyDetail);
        }
    }

    private final boolean enabled;
    private final RemoteSessionPolicyEngine engine;
    private final RemoteBridgePermitSigner signer;
    private final RemoteBridgeAuditSink auditSink;
    private final String policyVersion;
    private final long permitTtlMillis;
    private final long viewOnlyPermitTtlMillis;

    public RemoteBridgeBroker(boolean enabled, RemoteSessionPolicyEngine engine, RemoteBridgePermitSigner signer,
                              RemoteBridgeAuditSink auditSink, String policyVersion, long permitTtlMillis,
                              long viewOnlyPermitTtlMillis) {
        if (engine == null || signer == null || auditSink == null) {
            throw new IllegalArgumentException("engine, signer, auditSink are required");
        }
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("policyVersion must not be blank");
        }
        if (permitTtlMillis <= 0) {
            throw new IllegalArgumentException("permitTtlMillis must be positive");
        }
        if (viewOnlyPermitTtlMillis < 0 || viewOnlyPermitTtlMillis > MAX_VIEW_ONLY_PERMIT_TTL_MILLIS) {
            throw new IllegalArgumentException("viewOnlyPermitTtlMillis must be between 0 and 600000 inclusive");
        }
        this.enabled = enabled;
        this.engine = engine;
        this.signer = signer;
        this.auditSink = auditSink;
        this.policyVersion = policyVersion;
        this.permitTtlMillis = permitTtlMillis;
        this.viewOnlyPermitTtlMillis = viewOnlyPermitTtlMillis;
    }

    /**
     * Decide one operation. {@code seq} is the monotonic per-session sequence (replay guard) the transport
     * tracks; {@code nowEpochMillis} is the evaluation clock. Total, fail-closed.
     */
    public BrokerOutcome handle(OperationRequest request, RemoteBridgeTrustEvidence evidence, State sessionState,
                                long seq, long nowEpochMillis) {
        return handle(request, evidence, sessionState, seq, nowEpochMillis, null);
    }

    public BrokerOutcome handle(OperationRequest request, RemoteBridgeTrustEvidence evidence, State sessionState,
                                long seq, long nowEpochMillis, String policyEnvelopeDigest) {
        if (!enabled) {
            return BrokerOutcome.deny("remote-bridge-disabled");
        }
        if (request == null || evidence == null || sessionState == null || nowEpochMillis < 0 || seq < 0) {
            return BrokerOutcome.deny("malformed");
        }
        if (!WireContract.isValidId(request.sessionId()) || !WireContract.isValidId(request.operationId())
                || request.operation() == null) {
            return BrokerOutcome.deny("invalid-request");
        }
        // the broker-produced identity must itself be wire-valid (carries the T-1a id boundary into the permit)
        if (!WireContract.isValidId(evidence.deviceId()) || !WireContract.isValidId(evidence.operatorSubject())) {
            return BrokerOutcome.deny("invalid-identity");
        }
        // EXPLICIT pilot-operation allowlist (Codex): only screen-view + a constrained-PTY COMMAND are transport
        // operations — raw KEYBOARD_INPUT / MOUSE_INPUT / file / clipboard / pivot / credential / elevation are
        // NOT in the pilot transport even if a capability would otherwise permit them. Default-deny.
        RemoteOperation operation = request.operation();
        if (operation != RemoteOperation.SCREEN_VIEW && operation != RemoteOperation.PTY_COMMAND) {
            return BrokerOutcome.deny("unsupported-operation");
        }
        // operation <-> command rule (Codex): PTY needs a non-empty canonical command; a non-PTY op carries none
        boolean isPty = operation == RemoteOperation.PTY_COMMAND;
        CanonicalCommand command = CanonicalCommand.of(request.commandLine());
        if (isPty && command.isEmpty()) {
            return BrokerOutcome.deny("pty-without-command");
        }
        if (!isPty && request.commandLine() != null && !request.commandLine().isBlank()) {
            return BrokerOutcome.deny("non-pty-with-command");
        }
        if (!isPty && viewOnlyPermitTtlMillis == 0) {
            recordBestEffort(request.sessionId(), "DENY:VIEW_ONLY_PERMIT_DISABLED", "", nowEpochMillis);
            return BrokerOutcome.deny("view-only-permit-disabled");
        }
        if (!sessionState.isActive()) {
            return BrokerOutcome.deny("session-not-active");
        }
        if (!ConsentLease.isActive(evidence.consentLease(), nowEpochMillis)) {
            return BrokerOutcome.deny("no-active-consent-lease");
        }

        SessionContext ctx = new SessionContext(evidence.duressSignal(), evidence.certTrusted(),
                evidence.attestationVerified(), evidence.deviceTrusted(),
                evidence.deviceTrustBasis() == Basis.MACHINE_CERT_ENROLLMENT, evidence.stepUpState(),
                evidence.grantedCapabilities(), request.operation(), request.commandLine(), nowEpochMillis);
        SessionDecision decision = engine.evaluate(ctx);

        if (decision.outcome() == Outcome.TERMINATE_DURESS) {
            recordBestEffort(request.sessionId(), "KILL:DURESS", command.hash(), nowEpochMillis); // kill fires regardless
            return BrokerOutcome.kill(new Kill(request.sessionId(), "DURESS", nowEpochMillis));
        }
        if (decision.outcome() != Outcome.ALLOW) {
            recordBestEffort(request.sessionId(), "DENY:" + decision.gate(), command.hash(), nowEpochMillis);
            String detail = decision.gate() == RemoteSessionPolicyEngine.Gate.CRYPTO_IDENTITY
                    ? evidence.cryptoIdentityDetail() : null;
            return BrokerOutcome.deny("policy:" + decision.gate(), detail);
        }
        long selectedPermitTtlMillis = isPty ? permitTtlMillis : viewOnlyPermitTtlMillis;
        long permitExpiresAtEpochMillis;
        try {
            permitExpiresAtEpochMillis = Math.addExact(nowEpochMillis, selectedPermitTtlMillis);
        } catch (ArithmeticException e) {
            recordBestEffort(request.sessionId(), "DENY:PERMIT_EXPIRY_OVERFLOW", command.hash(), nowEpochMillis);
            return BrokerOutcome.deny("permit-expiry-overflow");
        }
        // ALLOW — the decision MUST be durably recorded BEFORE a permit is issued (fail-closed: ADR-0034). The
        // event is ALLOW_DECISION (the permit may still be refused by the signer below — it is not yet issued).
        try {
            auditSink.record(new AuditEvent(request.sessionId(), "ALLOW_DECISION:" + request.operationId(),
                    command.hash(), nowEpochMillis));
        } catch (RuntimeException e) {
            return BrokerOutcome.deny("recording-failed"); // no permit without a durable record
        }
        RemoteSessionCapability capability = isPty ? RemoteSessionCapability.CONSTRAINED_PTY
                : RemoteSessionCapability.VIEW_ONLY;
        String commandHash = isPty ? command.hash() : ""; // matches the signer's capability<->commandHash invariant
        int permitVersion = policyEnvelopeDigest == null || policyEnvelopeDigest.isBlank()
                ? RemoteBridgePermitSigner.PERMIT_VERSION : RemoteBridgePermitSigner.POLICY_BOUND_PERMIT_VERSION;
        OperationPermit unsigned = new OperationPermit(signer.alg(), signer.kid(),
                permitVersion, policyVersion, decisionId(request), request.sessionId(),
                request.operationId(), evidence.deviceId(), evidence.operatorSubject(), capability, commandHash,
                nowEpochMillis, permitExpiresAtEpochMillis, seq, policyEnvelopeDigest, null);
        // the signer is the final boundary — it refuses anything not a complete, consistent pilot permit
        return signer.sign(unsigned).map(BrokerOutcome::permit)
                .orElseGet(() -> BrokerOutcome.deny("permit-signing-refused"));
    }

    private static String decisionId(OperationRequest request) {
        return request.sessionId() + ":" + request.operationId();
    }

    /** Best-effort audit for a deny/kill (the safe outcome proceeds even if recording fails). */
    private void recordBestEffort(String sessionId, String eventType, String contentHash, long now) {
        try {
            auditSink.record(new AuditEvent(sessionId, eventType, contentHash, now));
        } catch (RuntimeException ignored) {
            // a deny/kill is already the safe outcome; a recording failure does not change it
        }
    }
}
