package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.MethodStrength;

/**
 * Faz 22.6 D operator step-up verifier seam (Codex 019ebe06) — verifies an operator's WebAuthn step-up
 * assertion against the broker-issued challenge and reports the achieved {@link MethodStrength}. This is the
 * PRODUCER of the step-up evidence that {@link OperatorStepUpPolicy} (the CONSUMER) already evaluates: the
 * policy decides whether an operation requires a fresh step-up; this verifier proves the operator actually did
 * one and how strong it was.
 *
 * <p><b>Foundation slice (d-stepup-1, B1.3a/B1.4c precedent):</b> interface + value objects + an in-memory
 * REFERENCE verifier. The real WebAuthn crypto verifier ({@code clientDataJSON} challenge/type/origin +
 * {@code authenticatorData} UP/UV flags + JCA signature over {@code authenticatorData || SHA256(clientDataJSON)},
 * Codex S3) is the next slice; the factory/guard (prod-forbidden in-memory) the one after. The verifier→
 * assembler wiring stays DEFERRED (the assembler keeps emitting the fail-closed weakest {@code StepUpState}
 * until the operator-facing transport that carries the assertion exists — slice-4c).
 *
 * <p><b>Fail-closed:</b> the verifier returns a {@link StepUpVerification}; only {@link Verdict#VERIFIED}
 * (with the achieved strength) is a step-up. The {@code StepUpState} mapping (lastStepUp timestamp + strength)
 * is the consumer/adapter's job and happens ONLY for VERIFIED — {@code sessionStart} is never the verifier's
 * to know, and a non-VERIFIED verdict maps to the weakest fail-closed state (Codex S2).
 */
public interface OperatorStepUpVerifier {

    /** The broker-issued step-up challenge the operator must sign (the freshness + replay anchor). */
    record StepUpChallenge(String challengeB64, String expectedOrigin, long issuedAtEpochMillis) {
    }

    /** The operator's WebAuthn assertion response (raw base64 fields; the real verifier parses them). */
    record StepUpAssertion(String clientDataJsonB64, String authenticatorDataB64, String signatureB64) {
    }

    /** Why a step-up assertion did or did not verify — internal audit detail, never an agent-facing oracle. */
    enum Verdict {
        VERIFIED,
        MISSING,
        MALFORMED,
        CHALLENGE_MISMATCH,
        ORIGIN_MISMATCH,
        RP_ID_MISMATCH,
        USER_PRESENCE_MISSING,
        SIGNATURE_INVALID
    }

    /**
     * The verification outcome: the verdict, the achieved method strength (only meaningful when VERIFIED), and
     * the timestamp the step-up was verified at. A non-VERIFIED result MUST carry {@link MethodStrength#NONE}.
     */
    record StepUpVerification(Verdict verdict, MethodStrength achievedStrength, long verifiedAtEpochMillis) {
        public StepUpVerification {
            if (verdict == null) {
                throw new IllegalArgumentException("verdict must not be null");
            }
            // a VERIFIED step-up MUST carry a real (non-NONE) strength — a "verified with no strength" is a
            // contradiction the policy could misread as a satisfied step-up (Codex REVISE, fail-closed invariant)
            if (verdict == Verdict.VERIFIED && (achievedStrength == null || achievedStrength == MethodStrength.NONE)) {
                throw new IllegalArgumentException("a VERIFIED step-up requires a non-NONE method strength");
            }
            // fail-closed: anything that is not an explicit VERIFIED carries NO strength
            achievedStrength = verdict == Verdict.VERIFIED ? achievedStrength : MethodStrength.NONE;
        }

        public boolean isVerified() {
            return verdict == Verdict.VERIFIED;
        }

        static StepUpVerification verified(MethodStrength strength, long verifiedAtEpochMillis) {
            return new StepUpVerification(Verdict.VERIFIED, strength, verifiedAtEpochMillis);
        }

        static StepUpVerification refused(Verdict verdict) {
            return new StepUpVerification(verdict, MethodStrength.NONE, 0L);
        }
    }

    /**
     * Verify the operator's step-up assertion against the challenge. MUST be total + fail-closed: a null/
     * malformed challenge or assertion, a challenge/origin mismatch, a missing user-presence, or a bad
     * signature all yield a non-VERIFIED {@link StepUpVerification} (never throws, never returns null).
     */
    StepUpVerification verify(StepUpChallenge challenge, StepUpAssertion assertion, long nowEpochMillis);
}
