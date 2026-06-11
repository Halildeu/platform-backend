package com.example.endpointadmin.remoteaccess;

import java.util.Map;

/**
 * Faz 22.6 D-6 — the operator step-up (re-authentication freshness) GATE (ADR-0033 §7, ADR-0034 D6/D8). A core
 * PAM / zero-trust control: before a {@link RemoteOperation} proceeds, the operator must hold a recent enough,
 * strong enough successful step-up (FIDO2/WebAuthn). The session always demands a fresh strong step-up at the
 * START (attended-only consent, ADR-0034 D6); the higher-risk CONSTRAINED_PTY operations then require a
 * STRONGER method within a SHORTER freshness window than low-risk SCREEN_VIEW. Pure, total, fail-closed.
 *
 * <p><b>Scope cut (Codex 019eb874):</b> this is the POLICY — "given what we know about the operator's last
 * step-up, is a fresh one required for this operation?" The actual WebAuthn assertion VERIFICATION (challenge/
 * nonce, credential store, attestation) is a transport slice; this gate consumes a {@link StepUpState} the
 * verifier produced. Pure + offline + deterministic (all times passed in; no system clock).
 *
 * <p><b>Fail-closed rules:</b>
 * <ul>
 *   <li>null operation / null state → {@link Decision#DENIED_MALFORMED} (a malformed call is a BUG — a distinct
 *       alarm semantic from the normal {@link Decision#REQUIRE_STEP_UP});</li>
 *   <li>clock skew {@code now < sessionStart} OR {@code now < lastStepUp} → REQUIRE_STEP_UP (never SATISFIED on a
 *       nonsensical interval);</li>
 *   <li>pre-session replay {@code lastStepUp < sessionStart} → REQUIRE_STEP_UP (a step-up from a PRIOR session
 *       cannot satisfy this one);</li>
 *   <li>weak method (below the operation's required strength) → REQUIRE_STEP_UP;</li>
 *   <li>stale ({@code now - lastStepUp > freshnessTtl}) → REQUIRE_STEP_UP.</li>
 * </ul>
 */
public final class OperatorStepUpPolicy {

    /** Authentication method strength, totally ordered by an explicit {@link #rank()} (NOT enum ordinal, so
     *  reordering / inserting a method never silently changes the comparison — Codex 019eb874). */
    public enum MethodStrength {
        NONE(0),
        OTP(1),
        WEBAUTHN_USER_PRESENCE(2),       // FIDO2 touch (presence) — no PIN/biometric
        WEBAUTHN_USER_VERIFICATION(3);   // FIDO2 UV — PIN/biometric, the strongest

        private final int rank;

        MethodStrength(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }

        /** True iff this method is at least as strong as {@code required}. */
        public boolean meetsRequired(MethodStrength required) {
            return required != null && this.rank >= required.rank();
        }
    }

    /** The explicit, auditable outcome of one step-up check. */
    public enum Decision {
        /** A recent, strong-enough, this-session step-up satisfies the operation. */
        SATISFIED(true),
        /** A fresh step-up is required (weak / stale / pre-session / clock-skew) — the normal re-auth path. */
        REQUIRE_STEP_UP(false),
        /** A null operation / state — a malformed call, fail-closed AND distinctly alarmed (a bug, not a re-auth). */
        DENIED_MALFORMED(false);

        private final boolean satisfied;

        Decision(boolean satisfied) {
            this.satisfied = satisfied;
        }

        public boolean satisfied() {
            return satisfied;
        }
    }

    /** What the runtime knows about the operator's most recent successful step-up (the verifier's output). */
    public record StepUpState(long lastStepUpEpochMillis, long sessionStartEpochMillis, MethodStrength methodStrength) {
        public StepUpState {
            methodStrength = methodStrength == null ? MethodStrength.NONE : methodStrength; // null → weakest, fail-closed
        }
    }

    /** A per-operation requirement: the minimum method strength and the freshness window. */
    public record Requirement(MethodStrength minStrength, long freshnessTtlMillis) {
        public Requirement {
            if (minStrength == null) {
                throw new IllegalArgumentException("minStrength must not be null");
            }
            if (freshnessTtlMillis < 0) {
                throw new IllegalArgumentException("freshnessTtlMillis must not be negative");
            }
        }
    }

    private final Map<RemoteOperation, Requirement> requirements;
    private final Requirement offForPilotDefault;

    /**
     * @param requirements        per-operation requirement
     * @param offForPilotDefault  the requirement for any operation NOT in the map — the strongest + shortest
     *                            gate (the OFF-for-pilot operations should never reach here, but if one does it
     *                            faces the hardest requirement, fail-closed).
     */
    public OperatorStepUpPolicy(Map<RemoteOperation, Requirement> requirements, Requirement offForPilotDefault) {
        if (requirements == null || offForPilotDefault == null) {
            throw new IllegalArgumentException("requirements and offForPilotDefault are required");
        }
        this.requirements = Map.copyOf(requirements);
        this.offForPilotDefault = offForPilotDefault;
    }

    private static final long MIN = 60_000L;

    /**
     * The pilot policy: a strong step-up at session start for the view, and a STRONGER (UV) + SHORTER (5 min)
     * gate for the constrained-PTY input operations. Every other (OFF-for-pilot) operation gets the strongest
     * method + the shortest (1 min) window.
     */
    public static final OperatorStepUpPolicy PILOT_DEFAULT_POLICY = new OperatorStepUpPolicy(
            Map.of(
                    RemoteOperation.SCREEN_VIEW,
                    new Requirement(MethodStrength.WEBAUTHN_USER_PRESENCE, 30 * MIN),
                    RemoteOperation.PTY_COMMAND,
                    new Requirement(MethodStrength.WEBAUTHN_USER_VERIFICATION, 5 * MIN),
                    RemoteOperation.KEYBOARD_INPUT,
                    new Requirement(MethodStrength.WEBAUTHN_USER_VERIFICATION, 5 * MIN)),
            new Requirement(MethodStrength.WEBAUTHN_USER_VERIFICATION, 1 * MIN));

    /**
     * Decide whether {@code operation} may proceed given the operator's {@code state} at {@code nowEpochMillis}.
     * Total, fail-closed — anything not provably a fresh, strong, this-session step-up requires a new one.
     */
    public Decision decide(RemoteOperation operation, StepUpState state, long nowEpochMillis) {
        if (operation == null || state == null) {
            return Decision.DENIED_MALFORMED;
        }
        Requirement required = requirements.getOrDefault(operation, offForPilotDefault);

        // corrupted/negative timestamps — a pre-epoch value is nonsensical; fail-closed, never SATISFIED (Codex 019eb874)
        if (state.sessionStartEpochMillis() < 0 || state.lastStepUpEpochMillis() < 0 || nowEpochMillis < 0) {
            return Decision.REQUIRE_STEP_UP;
        }
        // clock skew — a now() before the session or before the step-up is nonsensical → never SATISFIED
        if (nowEpochMillis < state.sessionStartEpochMillis() || nowEpochMillis < state.lastStepUpEpochMillis()) {
            return Decision.REQUIRE_STEP_UP;
        }
        // pre-session replay — a step-up from a prior session cannot satisfy this one
        if (state.lastStepUpEpochMillis() < state.sessionStartEpochMillis()) {
            return Decision.REQUIRE_STEP_UP;
        }
        // method strength
        if (!state.methodStrength().meetsRequired(required.minStrength())) {
            return Decision.REQUIRE_STEP_UP;
        }
        // freshness
        if (nowEpochMillis - state.lastStepUpEpochMillis() > required.freshnessTtlMillis()) {
            return Decision.REQUIRE_STEP_UP;
        }
        return Decision.SATISFIED;
    }
}
