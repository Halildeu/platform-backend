package com.example.endpointadmin.remoteaccess;

/**
 * Faz 22.6 D-7 — the coercion / DURESS response policy (ADR-0033 §7, ADR-0034 D6). A high-privilege attended
 * remote-support operator can be physically coerced; the system needs a duress path — the operator silently
 * signals duress and the session is terminated WITHOUT tipping off the coercer, with a covert security alert
 * and preserved evidence. This is the RESPONSE policy: "given a duress signal, what is the mandated response?"
 * Pure, total, fail-SAFE.
 *
 * <p><b>Scope cut (Codex 019eb874), same shape as the step-up gate:</b> this slice does NOT detect duress — the
 * SIGNAL DETECTION mechanism (a duress code/PIN, a panic gesture, the operator UX) is an owner decision +
 * transport concern, deferred. This consumes a {@link DuressSignal} the verifier/transport classified and
 * emits a {@link DuressResponse} the transport executes (terminate, suppress feedback, send the covert alert).
 *
 * <p><b>Fail-SAFE, not fail-closed-toward-deny:</b> the gates (D-1…D-6) fail toward DENY because the protected
 * asset is access. Here the protected asset is the HUMAN, so ambiguity fails toward ASSUMING DURESS: a missed
 * real duress is catastrophic (operator harm), while a false duress only ends one recoverable support session.
 * Hence {@link DuressSignal#AMBIGUOUS}, a {@code null} signal, and any future/unknown signal all map to the
 * full duress response.
 *
 * <p><b>Deferred to the transport (NOT this pure slice):</b> covert-alert de-duplication / throttling for a
 * repeated signal on the same session; the alert routing targets (SOC / on-call); a generated correlationId
 * (needs randomness/clock, which this deterministic policy must not use). The transport attaches those when it
 * executes the response.
 */
public final class DuressResponsePolicy {

    /** The verifier/transport's classification of what the operator did (this slice consumes, never detects, it). */
    public enum DuressSignal { NONE, DURESS_CODE, PANIC_SIGNAL, AMBIGUOUS }

    public enum Severity { NONE, CRITICAL }

    /**
     * The mandated action set for the transport to execute. On duress everything is terminated silently, an
     * out-of-band alert is raised, and the WORM recording is preserved + flagged as the evidence (never dropped).
     */
    public record DuressResponse(boolean terminateSession,
                                 boolean terminateAllOperatorSessions,
                                 boolean silentToOperator,
                                 boolean covertAlert,
                                 boolean preserveRecording,
                                 boolean requireIncidentReview,
                                 Severity severity) {

        /** True when this response mandates a duress termination (vs. proceed normally). */
        public boolean isDuress() {
            return terminateSession;
        }
    }

    /** No duress — proceed normally; nothing is terminated, alerted, or flagged. */
    public static final DuressResponse PROCEED =
            new DuressResponse(false, false, false, false, false, false, Severity.NONE);

    /**
     * The mandated duress response: terminate this session AND all of the operator's linked sessions/control
     * channels; suppress all operator-visible feedback (the coercer must see a normal-looking outcome); raise a
     * covert out-of-band alert; preserve the WORM recording as evidence; require a human incident review;
     * severity CRITICAL.
     */
    public static final DuressResponse DURESS =
            new DuressResponse(true, true, true, true, true, true, Severity.CRITICAL);

    private DuressResponsePolicy() {
    }

    /**
     * Map a duress signal to the mandated response. Fail-SAFE: a {@code null}, AMBIGUOUS, or any future/unknown
     * signal yields the full duress response — a lost or unrecognised signal must NEVER silently mean "no duress".
     */
    public static DuressResponse responseFor(DuressSignal signal) {
        if (signal == null) {
            return DURESS; // a lost signal must never silently mean "no duress"
        }
        return switch (signal) {
            case NONE -> PROCEED;
            // DURESS_CODE, PANIC_SIGNAL, AMBIGUOUS, and any signal added in future -> fail-safe duress
            default -> DURESS;
        };
    }
}
