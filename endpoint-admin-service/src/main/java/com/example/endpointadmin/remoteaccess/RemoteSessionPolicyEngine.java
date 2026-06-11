package com.example.endpointadmin.remoteaccess;

import java.util.Set;

/**
 * Faz 22.6 — the remote-session policy ENGINE: the single decision core the C/D transport calls per operation,
 * composing the already-merged pure gates into ONE priority-ordered, fail-closed verdict (ADR-0033 §7,
 * ADR-0034 D6/D8). It is the integration point between the policy primitives (B1 crypto-identity + D-1..D-7)
 * and the live transport — the transport supplies the {@link SessionContext} and EXECUTES the
 * {@link SessionDecision} (allow the operation, deny it, or terminate on duress). Pure/total/fail-closed,
 * zero ripple.
 *
 * <p><b>Composition cut (Codex 019eb874):</b> the engine RUNS the pure-policy gates it owns (D-1
 * {@link RemoteOperationGuard}, D-6 {@link OperatorStepUpPolicy}, D-2+D-3 {@link ConstrainedPtyGate}, and D-7
 * {@link DuressResponsePolicy} statically) and COMBINES the CRYPTO-identity results (B1.2 cert-trust, B1.4c
 * attestation, B1.4d device-identity) as pre-computed booleans the transport supplies — those verifiers need
 * trust anchors / keys the transport holds, so the engine stays config-free.
 *
 * <p><b>Priority order (first failure wins — fail-closed, least-info-leak):</b>
 * <ol>
 *   <li>a {@code null} context → {@link Outcome#DENY} {@link Gate#MALFORMED} (an unreadable context yields no
 *       safe signal);</li>
 *   <li><b>DURESS overrides everything readable</b> — a duress signal {@link Outcome#TERMINATE_DURESS} even if
 *       the rest of the context is malformed or the operation would be denied (the human-safety override
 *       outranks access control). A {@code null} duress signal fail-safes to duress (D-7), so the transport
 *       MUST supply {@link DuressResponsePolicy.DuressSignal#NONE} when duress detection is active and clear;</li>
 *   <li>remaining null fields (operation / granted / step-up state) → MALFORMED;</li>
 *   <li>crypto-identity preconditions (cert → device → attestation);</li>
 *   <li>step-up freshness;</li>
 *   <li>operation ↔ capability;</li>
 *   <li>for {@link RemoteOperation#PTY_COMMAND}, the constrained-PTY command + argument gate.</li>
 * </ol>
 *
 * <p>The {@link SessionDecision#detail()} string is for the INTERNAL security log / telemetry only — never an
 * operator-facing message (the operator sees allow / deny; the failing gate is audit data).
 */
public final class RemoteSessionPolicyEngine {

    public enum Outcome { ALLOW, DENY, TERMINATE_DURESS }

    /** The gate that decided the verdict (audit/telemetry — internal only). */
    public enum Gate { NONE, MALFORMED, DURESS, CRYPTO_IDENTITY, STEP_UP, OPERATION, COMMAND }

    /** The composed verdict. {@code detail} is internal-log only. */
    public record SessionDecision(Outcome outcome, Gate gate, String detail) {
        public boolean allowed() {
            return outcome == Outcome.ALLOW;
        }
    }

    /**
     * Everything the engine needs for one operation decision. The crypto-identity results are pre-computed by
     * the transport (the verifiers hold the anchors/keys). {@code commandLine} is required only when
     * {@code operation == PTY_COMMAND}.
     */
    public record SessionContext(DuressResponsePolicy.DuressSignal duressSignal,
                                 boolean certTrusted,
                                 boolean attestationVerified,
                                 boolean deviceTrusted,
                                 OperatorStepUpPolicy.StepUpState stepUpState,
                                 Set<RemoteSessionCapability> granted,
                                 RemoteOperation operation,
                                 String commandLine,
                                 long nowEpochMillis) {
    }

    private final RemoteOperationGuard operationGuard;
    private final OperatorStepUpPolicy stepUpPolicy;
    private final ConstrainedPtyGate ptyGate;

    public RemoteSessionPolicyEngine(RemoteOperationGuard operationGuard,
                                     OperatorStepUpPolicy stepUpPolicy,
                                     ConstrainedPtyGate ptyGate) {
        if (operationGuard == null || stepUpPolicy == null || ptyGate == null) {
            throw new IllegalArgumentException("all three gates are required");
        }
        this.operationGuard = operationGuard;
        this.stepUpPolicy = stepUpPolicy;
        this.ptyGate = ptyGate;
    }

    /** The pilot engine — composes the D-1 (pilot strictness), D-6, and D-2+D-3 pilot defaults. */
    public static final RemoteSessionPolicyEngine PILOT = new RemoteSessionPolicyEngine(
            new RemoteOperationGuard(true),
            OperatorStepUpPolicy.PILOT_DEFAULT_POLICY,
            ConstrainedPtyGate.PILOT);

    /** Evaluate one operation. Total, fail-closed; first failure (in priority order) wins. */
    public SessionDecision evaluate(SessionContext ctx) {
        if (ctx == null) {
            return deny(Gate.MALFORMED, "null context");
        }
        // DURESS overrides everything readable — the human-safety override outranks access control
        if (DuressResponsePolicy.responseFor(ctx.duressSignal()).isDuress()) {
            return new SessionDecision(Outcome.TERMINATE_DURESS, Gate.DURESS, "duress signal");
        }
        if (ctx.operation() == null) {
            return deny(Gate.MALFORMED, "null operation");
        }
        if (ctx.granted() == null) {
            return deny(Gate.MALFORMED, "null capability set");
        }
        if (ctx.stepUpState() == null) {
            return deny(Gate.MALFORMED, "null step-up state");
        }
        if (ctx.nowEpochMillis() < 0) {
            return deny(Gate.MALFORMED, "negative timestamp"); // a pre-epoch clock is nonsensical (Codex 019eb874)
        }
        // crypto-identity preconditions (cert → device → attestation)
        if (!ctx.certTrusted()) {
            return deny(Gate.CRYPTO_IDENTITY, "cert untrusted");
        }
        if (!ctx.deviceTrusted()) {
            return deny(Gate.CRYPTO_IDENTITY, "device untrusted");
        }
        if (!ctx.attestationVerified()) {
            return deny(Gate.CRYPTO_IDENTITY, "attestation unverified");
        }
        // step-up freshness
        if (!stepUpPolicy.decide(ctx.operation(), ctx.stepUpState(), ctx.nowEpochMillis()).satisfied()) {
            return deny(Gate.STEP_UP, "fresh step-up required");
        }
        // operation ↔ capability
        if (!operationGuard.decide(ctx.granted(), ctx.operation()).allowed()) {
            return deny(Gate.OPERATION, "operation not permitted by granted capabilities");
        }
        // constrained-PTY command + argument gate (only for PTY commands)
        if (ctx.operation() == RemoteOperation.PTY_COMMAND && !ptyGate.permits(ctx.commandLine())) {
            return deny(Gate.COMMAND, "constrained-PTY command not permitted");
        }
        return new SessionDecision(Outcome.ALLOW, Gate.NONE, "all gates passed");
    }

    private static SessionDecision deny(Gate gate, String detail) {
        return new SessionDecision(Outcome.DENY, gate, detail);
    }
}
