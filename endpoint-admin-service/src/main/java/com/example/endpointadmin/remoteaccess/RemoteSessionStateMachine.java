package com.example.endpointadmin.remoteaccess;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.example.endpointadmin.remoteaccess.RemoteSessionState.*;

/**
 * Faz 22.6 broker session state machine (ADR-0033 §3). Pure, side-effect-free policy logic —
 * no tunnel, no I/O. Enforces:
 * <ul>
 *   <li><b>Fail-closed {@code ACTIVE} invariant:</b> a session may enter {@link RemoteSessionState#ACTIVE}
 *       only when {@link RemoteSessionPreconditions#allSatisfied()} (policy ∧ consent ∧ dual-approval ∧
 *       token ∧ attestation ∧ recording-ack). Otherwise it stays pending or fails — no channel opens.</li>
 *   <li><b>Abort beats connect:</b> a terminal/abort transition is always allowed from any non-terminal
 *       state, even mid-connect.</li>
 *   <li><b>Terminal irreversibility + monotonicity:</b> no transition may leave a terminal state.</li>
 *   <li><b>Idempotence:</b> a self-transition (same state) is a no-op allowed.</li>
 * </ul>
 */
public final class RemoteSessionStateMachine {

    /** Abort/terminal states reachable from ANY non-terminal state ("abort beats connect"). */
    private static final Set<RemoteSessionState> ABORT_TERMINALS =
            EnumSet.of(DENIED, EXPIRED, REVOKED, ABORTED, FAILED_POLICY, FAILED_RECORDING, FAILED_AGENT_ATTESTATION);

    /** Allowed forward (happy-path) transitions. ACTIVE is reachable ONLY via the guarded method. */
    private static final Map<RemoteSessionState, Set<RemoteSessionState>> FORWARD = Map.ofEntries(
            Map.entry(REQUESTED, EnumSet.of(POLICY_EVALUATING)),
            Map.entry(POLICY_EVALUATING, EnumSet.of(PENDING_TARGET_CONSENT)),
            Map.entry(PENDING_TARGET_CONSENT, EnumSet.of(PENDING_DUAL_APPROVAL)),
            Map.entry(PENDING_DUAL_APPROVAL, EnumSet.of(APPROVED)),
            Map.entry(APPROVED, EnumSet.of(TOKEN_ISSUED)),
            Map.entry(TOKEN_ISSUED, EnumSet.of(AGENT_CONNECTED)),
            Map.entry(AGENT_CONNECTED, EnumSet.of(OPERATOR_CONNECTED)),
            Map.entry(OPERATOR_CONNECTED, EnumSet.of(RECORDING_READY)),
            // RECORDING_READY → ACTIVE is intentionally NOT here; only canActivate() may grant it.
            Map.entry(RECORDING_READY, EnumSet.noneOf(RemoteSessionState.class)),
            Map.entry(ACTIVE, EnumSet.of(ENDING)),
            Map.entry(ENDING, EnumSet.of(ENDED)));

    public RemoteSessionStateMachine() {
    }

    /**
     * @return whether {@code from → to} is a legal transition. Rules: terminal states are sinks;
     *         self-transition is an allowed no-op (idempotent); abort terminals are reachable from any
     *         non-terminal; ACTIVE is NEVER reachable here (use {@link #canActivate}).
     */
    public boolean canTransition(RemoteSessionState from, RemoteSessionState to) {
        if (from == null || to == null) {
            return false;
        }
        if (from.isTerminal()) {
            return false; // monotonic: terminal is irreversible
        }
        if (from == to) {
            return true; // idempotent no-op
        }
        if (to == ACTIVE) {
            return false; // ACTIVE requires the precondition-guarded path
        }
        if (ABORT_TERMINALS.contains(to)) {
            return true; // abort beats connect
        }
        return FORWARD.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Total, side-effect-free, non-throwing evaluation of whether a session may go ACTIVE
     * (Codex 019eb522 REVISE absorb: a swallowed exception must not leave a session mis-stated —
     * callers get an explicit, auditable outcome for every input instead of a throw).
     */
    public enum ActivationOutcome {
        /** All preconditions satisfied at RECORDING_READY → ACTIVE permitted. */
        GRANTED,
        /** Recoverable preconditions still pending (consent / dual-approval / token, or wrong stage). */
        BLOCKED_PENDING,
        /** Hard fail-closed terminals. */
        FAILED_POLICY,
        FAILED_RECORDING,
        FAILED_AGENT_ATTESTATION
    }

    /**
     * Evaluate the activation decision for {@code (from, pre)} as a TOTAL function — never throws,
     * always returns an explicit outcome. This is the auditable entry point; {@link #transition} and
     * {@link #canActivate} are thin wrappers. Fail-closed: a {@code null} precondition set is treated
     * as {@link ActivationOutcome#FAILED_POLICY}; any non-{@code GRANTED} outcome means NO interactive
     * channel opens.
     */
    /**
     * The single guarantee whose loss is reported first, in security-precedence order. Shared by
     * {@link #evaluateActivation} and {@link #reevaluateActive} so both compute the SAME decision from
     * one place (Codex 019eb54b absorb — single source of truth, no drift). Caller must pass non-null.
     */
    public enum LostGuarantee { NONE, POLICY, ATTESTATION, RECORDER, CONSENT, DUAL_APPROVAL, TOKEN }

    static LostGuarantee firstLost(RemoteSessionPreconditions pre) {
        if (!pre.policyAllow()) {
            return LostGuarantee.POLICY;
        }
        if (!pre.agentAttestation()) {
            return LostGuarantee.ATTESTATION;
        }
        if (!pre.recordingWriterAck()) {
            return LostGuarantee.RECORDER; // recording is atomic with the session
        }
        if (!pre.targetConsent()) {
            return LostGuarantee.CONSENT;
        }
        if (!pre.dualApproval()) {
            return LostGuarantee.DUAL_APPROVAL;
        }
        if (!pre.tokenBound()) {
            return LostGuarantee.TOKEN;
        }
        return LostGuarantee.NONE;
    }

    public ActivationOutcome evaluateActivation(RemoteSessionState from, RemoteSessionPreconditions pre) {
        if (pre == null) {
            return ActivationOutcome.FAILED_POLICY;
        }
        if (from != RECORDING_READY) {
            return ActivationOutcome.BLOCKED_PENDING; // not yet at the activation stage
        }
        return switch (firstLost(pre)) {
            case NONE -> ActivationOutcome.GRANTED;
            case POLICY -> ActivationOutcome.FAILED_POLICY;
            case ATTESTATION -> ActivationOutcome.FAILED_AGENT_ATTESTATION;
            case RECORDER -> ActivationOutcome.FAILED_RECORDING;
            // consent / dual-approval / token are recoverable — caller stays PENDING_* + audits
            case CONSENT, DUAL_APPROVAL, TOKEN -> ActivationOutcome.BLOCKED_PENDING;
        };
    }

    /**
     * The ONLY boolean grant for {@link RemoteSessionState#ACTIVE} (fail-closed). Requires the current
     * state to be {@code RECORDING_READY} AND every precondition satisfied.
     *
     * @return {@code true} iff the session may legally go ACTIVE now.
     */
    public boolean canActivate(RemoteSessionState from, RemoteSessionPreconditions pre) {
        return evaluateActivation(from, pre) == ActivationOutcome.GRANTED;
    }

    /**
     * Continuous mid-session re-evaluation hook (ADR-0033 §9b — Codex 019eb54b absorb: the ACTIVE
     * invariant is NOT only an activation-time check). A runtime heartbeat calls this every ≤N seconds
     * with the CURRENT preconditions; if any was lost while the session is live, the session must be
     * killed immediately (fail-closed) — TOCTOU / capability-drift / revocation guard. Pure + total.
     *
     * @param current the session's current state
     * @param now     the freshly-sampled preconditions (policy still allow? token still valid? consent
     *                still held? dual-approval still valid? recorder still healthy?)
     * @return a {@link Reevaluation} with the target state to move to + a precise {@link KillReason}
     *         (fed to the audit record's {@code abortReason}). {@code ACTIVE}/{@code NONE} = stay live;
     *         hard losses map to {@code FAILED_*}; consent/approval/token loss + visibility loss map to
     *         {@code ABORTED} with a distinct reason. For a non-ACTIVE state this is a no-op.
     */
    public Reevaluation reevaluateActive(RemoteSessionState current, RemoteSessionPreconditions now) {
        if (current != ACTIVE) {
            return new Reevaluation(current, KillReason.NOT_ACTIVE); // only governs live sessions
        }
        if (now == null) {
            return new Reevaluation(ABORTED, KillReason.VISIBILITY_LOSS); // fail-closed: lost visibility → kill
        }
        return switch (firstLost(now)) {
            case NONE -> new Reevaluation(ACTIVE, KillReason.NONE); // all guarantees still hold → stay live
            case POLICY -> new Reevaluation(FAILED_POLICY, KillReason.POLICY_REVOKED);
            case ATTESTATION -> new Reevaluation(FAILED_AGENT_ATTESTATION, KillReason.ATTESTATION_LOST);
            // recorder died → no unrecorded ACTIVE may continue
            case RECORDER -> new Reevaluation(FAILED_RECORDING, KillReason.RECORDER_LOST);
            case CONSENT -> new Reevaluation(ABORTED, KillReason.CONSENT_REVOKED);
            case DUAL_APPROVAL -> new Reevaluation(ABORTED, KillReason.DUAL_APPROVAL_REVOKED);
            case TOKEN -> new Reevaluation(ABORTED, KillReason.TOKEN_REVOKED);
        };
    }

    /** Precise audit reason for a mid-session kill (feeds {@code RemoteSessionAuditRecord.abortReason}). */
    public enum KillReason {
        NONE, NOT_ACTIVE, POLICY_REVOKED, ATTESTATION_LOST, RECORDER_LOST,
        CONSENT_REVOKED, DUAL_APPROVAL_REVOKED, TOKEN_REVOKED, VISIBILITY_LOSS,
        // refined token-loss causes the heartbeat distinguishes from a plain revocation (Codex 019eb54b
        // absorb — accurate audit/IR root-cause, not all token-loss is "revoked"):
        TOKEN_EXPIRED, TOKEN_NOT_FOUND, STORE_UNAVAILABLE,
        // a live session that stopped receiving fresh heartbeats (seq-independent timeout):
        HEARTBEAT_TIMEOUT
    }

    /**
     * Heartbeat re-evaluation result: the target state + the precise audit reason. {@link #isKill()}
     * iff the live session must be terminated now.
     */
    public record Reevaluation(RemoteSessionState target, KillReason reason) {
        public boolean isKill() {
            return reason != KillReason.NONE && reason != KillReason.NOT_ACTIVE;
        }
    }

    /**
     * Stale-event / monotonicity guard for the heartbeat (Codex 019eb54b absorb): a sampled heartbeat
     * must be applied only if it is strictly newer than the last applied one, so a delayed/out-of-order
     * sample can never "rewind" the session (resurrect a killed session, or undo a newer kill). The
     * runtime supplies a monotonic sequence / timestamp source.
     *
     * @return {@code true} iff {@code incomingSeq} is fresh (strictly greater than {@code lastAppliedSeq}).
     */
    public static boolean isFreshSample(long incomingSeq, long lastAppliedSeq) {
        return incomingSeq > lastAppliedSeq;
    }

    /**
     * Resolve the next state given the current state + preconditions, treating ACTIVE specially.
     * If the caller intends ACTIVE but preconditions are not met, this returns the appropriate
     * {@code FAILED_*} terminal (or leaves the session pending) — never ACTIVE.
     *
     * @throws IllegalStateTransitionException if {@code intended} is not a legal transition.
     */
    public RemoteSessionState transition(RemoteSessionState from, RemoteSessionState intended,
                                         RemoteSessionPreconditions pre) {
        // Apply the same monotonicity guards as canTransition() FIRST, before the ACTIVE arm, so the
        // ACTIVE branch can never bypass them (Codex 019eb522 consistency absorb):
        if (from == null || intended == null) {
            throw new IllegalStateTransitionException(from, intended, "null state");
        }
        if (from.isTerminal()) {
            throw new IllegalStateTransitionException(from, intended, "terminal is irreversible");
        }
        if (from == intended) {
            return from; // idempotent self-no-op (incl. ACTIVE→ACTIVE — no precondition re-check)
        }
        if (intended == ACTIVE) {
            // Delegate to the total evaluation so the outcome is explicit + fail-closed for every input.
            ActivationOutcome outcome = evaluateActivation(from, pre);
            return switch (outcome) {
                case GRANTED -> ACTIVE;
                case FAILED_POLICY -> FAILED_POLICY;
                case FAILED_RECORDING -> FAILED_RECORDING;
                case FAILED_AGENT_ATTESTATION -> FAILED_AGENT_ATTESTATION;
                // BLOCKED_PENDING is NOT a state change — throwing keeps fail-closed (no ACTIVE). Callers
                // wanting an auditable non-throwing decision use evaluateActivation() directly.
                case BLOCKED_PENDING -> throw new IllegalStateTransitionException(from, ACTIVE,
                        "ACTIVE preconditions pending (consent/dual-approval/token or wrong stage)");
            };
        }
        if (!canTransition(from, intended)) {
            throw new IllegalStateTransitionException(from, intended, "illegal transition");
        }
        return intended;
    }

    /** Thrown when an illegal session transition is attempted. */
    public static final class IllegalStateTransitionException extends RuntimeException {
        public IllegalStateTransitionException(RemoteSessionState from, RemoteSessionState to, String why) {
            super("remote-session illegal transition " + from + " → " + to + ": " + why);
        }
    }
}
