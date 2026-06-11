package com.example.endpointadmin.remoteaccess;

import java.time.Duration;
import java.time.Instant;

/**
 * Faz 22.6 B2.2 — the heartbeat orchestrator: the "brain" of the continuous-re-evaluation / hard-kill loop
 * (ADR-0033 §9b, Codex 019eb54b criteria #2/#4/#5/#6). Pure + total — NO scheduling, NO I/O beyond the
 * injected {@link TokenLifecycleStore} read; the live {@code @Scheduled} driver + the prod store backing
 * (Redis Lua / DB CAS) are B2.2b. Disabled-by-default.
 *
 * <p>Per sample it: (0) <b>heartbeat-timeout kill</b> — a live session whose last fresh sample is older
 * than {@code maxHeartbeatAge} is killed even if no fresh sample arrives (seq-independent, fail-closed),
 * (1) rejects a stale/out-of-order sample (monotonicity — can't rewind a killed session), (2) refreshes
 * {@code tokenBound} AND the cert-binding from ONE atomic {@link TokenLifecycleStore#status} read with the
 * precise root cause, (3) checks the PRESENTED client-cert thumbprint against the BOUND one (B1.1c,
 * RFC 8705 — mismatch/missing presented on a bound token, or a legacy-unbound token under
 * {@link CertBindingGuard.Policy#REQUIRE_BOUND}, → fail-closed kill), (4) re-evaluates the ACTIVE
 * invariant, (5) on a kill measures {@code revokedAt → now} latency (clamped ≥ 0; a negative skew is
 * flagged, not silently zeroed) for the {@code revocation_latency_ms} SLO (P95 ≤ 5s).
 */
public final class RemoteSessionHeartbeat {

    private final TokenLifecycleStore store;
    private final RemoteSessionStateMachine stateMachine;
    private final Duration maxHeartbeatAge;
    private final CertBindingGuard.Policy certBindingPolicy;
    private final CertTrustEvaluator trustEvaluator;
    private final AttestationVerifier attestationVerifier;
    private final CertRef expectedCertIdentity;

    /**
     * @param certBindingPolicy    the legacy-unbound feature flag (B1.1c). {@code null} is coerced to
     *                             {@link CertBindingGuard.Policy#REQUIRE_BOUND} (fail-closed default).
     * @param trustEvaluator       the B1.2 cert-trust source (chain + CRL/OCSP). {@code null} is coerced to a
     *                             deny-all evaluator (fail-closed: an absent trust source can't prove trust →
     *                             NOT_TRUSTED → kill any cert-sampling session).
     * @param attestationVerifier  the B1.3 agent-provenance source (SLSA/builder/signed-predicate). {@code null}
     *                             is coerced to a deny-all verifier (fail-closed: an absent provenance source
     *                             can't prove a build → MISSING → the {@code agentAttestation} precondition
     *                             fails → kill any cert-sampling session). Symmetric to {@code trustEvaluator}:
     *                             an enabled runtime with NO configured attestation policy refuses every live
     *                             session until the expected builder/policy is supplied (D10).
     * @param expectedCertIdentity the B1.4a-0 identity pin (expected agent-CA issuer DN, and a future bound
     *                             serial) checked against the presented cert. {@code null} = NOT enforced — an
     *                             ADDITIVE hardening (the thumbprint binding + trust still protect the
     *                             session), opt-in by config, so its absence is legitimately "identity not
     *                             constrained", NOT fail-closed (unlike {@code trustEvaluator}/
     *                             {@code attestationVerifier}, whose absence denies). When SET, a presented
     *                             cert from another CA — or with no issuer — is a fail-closed kill.
     */
    public RemoteSessionHeartbeat(TokenLifecycleStore store, RemoteSessionStateMachine stateMachine,
                                  Duration maxHeartbeatAge, CertBindingGuard.Policy certBindingPolicy,
                                  CertTrustEvaluator trustEvaluator, AttestationVerifier attestationVerifier,
                                  CertRef expectedCertIdentity) {
        this.store = store;
        this.stateMachine = stateMachine;
        this.maxHeartbeatAge = maxHeartbeatAge;
        this.certBindingPolicy =
                certBindingPolicy == null ? CertBindingGuard.Policy.REQUIRE_BOUND : certBindingPolicy;
        this.trustEvaluator = trustEvaluator == null
                ? (cert, now) -> CertTrustEvaluator.TrustDecision.NOT_TRUSTED
                : trustEvaluator;
        this.attestationVerifier = attestationVerifier == null
                ? (evidence, now) -> AttestationVerifier.AttestationDecision.MISSING
                : attestationVerifier;
        this.expectedCertIdentity = expectedCertIdentity; // null = identity not enforced (additive, opt-in)
    }

    /**
     * @param lastFreshAt when the last FRESH sample was applied (heartbeat-timeout anchor; nullable)
     */
    public record SessionSnapshot(
            String sessionId, String jti, RemoteSessionState state, long lastAppliedSeq, Instant lastFreshAt) {
    }

    /**
     * One heartbeat's runtime observation. Build via {@link #withCert} (cert-sampling instruments — the
     * live agent/tunnel heartbeat, which sees the TLS layer AND the agent's presented provenance) or
     * {@link #certUnsampled} (token-backstop instruments — the store-driven revocation reconciler, which has
     * NO transport view and must not degrade the cert/attestation guarantees it cannot observe).
     *
     * @param agentAttestation    the last-known attestation boolean — used ONLY on the {@code certUnsampled}
     *                            (token-backstop) path; on the {@code certSampled} live path it is IGNORED
     *                            and the verdict is COMPUTED from {@code attestationEvidence} (B1.3b).
     * @param certSampled         whether this sample carries a transport view (presented thumbprint +
     *                            attestation evidence). When {@code false}, the cert-binding, cert-trust AND
     *                            attestation preconditions are NOT evaluated for this sample (pass-through) —
     *                            ONLY for instruments that structurally cannot see the transport; the live
     *                            heartbeat path MUST use {@link #withCert}, where a missing presented cert on
     *                            a bound token, an untrusted cert, or unverified provenance is a fail-closed
     *                            kill.
     * @param presentedThumbprint   the live TLS-layer client-cert SHA-256 thumbprint ({@code null} = none)
     * @param presentedSerialNumber the live cert's serial number (B1.4a-0 identity; {@code null} if unknown)
     * @param presentedIssuerDn     the live cert's issuer DN (B1.4a-0 identity pin; {@code null} if unknown —
     *                              fail-closed against a configured issuer pin)
     * @param attestationEvidence   the agent's presented SLSA/builder/signed-predicate provenance (B1.3b),
     *                              verified live ({@code null}/incomplete ⇒ MISSING ⇒ fail-closed kill on the
     *                              cert-sampling path). Ignored on the {@code certUnsampled} path.
     * @param revokedAt             the t0 of a pending revocation (for SLO latency), or {@code null} if none
     */
    public record PreconditionSample(
            boolean policyAllow,
            boolean targetConsent,
            boolean dualApproval,
            boolean agentAttestation,
            boolean recordingWriterAck,
            boolean certSampled,
            String presentedThumbprint,
            String presentedSerialNumber,
            String presentedIssuerDn,
            AttestationEvidence attestationEvidence,
            Instant revokedAt) {

        /**
         * Cert-sampling sample (the live heartbeat path), thumbprint-only identity. The presented thumbprint
         * AND the attestation evidence ARE enforced; {@code agentAttestation} is COMPUTED from the evidence
         * (B1.3b), so the record's boolean field is pinned {@code false} (a mis-wired read can only
         * fail-closed). Carries NO serial/issuer — if a B1.4a-0 issuer pin is configured, such a sample
         * fail-closes as ISSUER_MISSING; the live runtime that knows the full cert identity uses
         * {@link #withCertIdentity}.
         */
        public static PreconditionSample withCert(
                boolean policyAllow, boolean targetConsent, boolean dualApproval,
                AttestationEvidence attestationEvidence, boolean recordingWriterAck,
                String presentedThumbprint, Instant revokedAt) {
            return new PreconditionSample(policyAllow, targetConsent, dualApproval,
                    false, recordingWriterAck, true, presentedThumbprint, null, null, attestationEvidence,
                    revokedAt);
        }

        /**
         * Cert-sampling sample carrying the FULL presented cert identity (thumbprint + serial + issuer DN),
         * so a B1.4a-0 issuer/serial pin can be enforced (the live cert-sampling runtime path). Same
         * attestation semantics as {@link #withCert}.
         */
        public static PreconditionSample withCertIdentity(
                boolean policyAllow, boolean targetConsent, boolean dualApproval,
                AttestationEvidence attestationEvidence, boolean recordingWriterAck,
                String presentedThumbprint, String presentedSerialNumber, String presentedIssuerDn,
                Instant revokedAt) {
            return new PreconditionSample(policyAllow, targetConsent, dualApproval,
                    false, recordingWriterAck, true, presentedThumbprint, presentedSerialNumber,
                    presentedIssuerDn, attestationEvidence, revokedAt);
        }

        /**
         * Token-backstop sample (no transport view, e.g. the revocation reconciler) — cert AND attestation
         * pass-through (it sees neither the TLS layer nor the agent's presented provenance). The
         * {@code agentAttestation} boolean is the last-known value the backstop asserts; it MUST NOT
         * re-derive a kill from a guarantee it cannot observe (cert + attestation enforcement stay with the
         * cert-sampling live heartbeat).
         */
        public static PreconditionSample certUnsampled(
                boolean policyAllow, boolean targetConsent, boolean dualApproval,
                boolean agentAttestation, boolean recordingWriterAck, Instant revokedAt) {
            return new PreconditionSample(policyAllow, targetConsent, dualApproval,
                    agentAttestation, recordingWriterAck, false, null, null, null, null, revokedAt);
        }
    }

    /**
     * @param applied       false iff the sample was rejected as stale (no state change — anti-rewind)
     * @param latencyMillis t0→decision latency (clamped ≥ 0) when this produced a kill, else 0
     * @param clockSkew     true iff {@code now < revokedAt} on a kill (the latency is unreliable → meter it)
     */
    public record HeartbeatDecision(
            RemoteSessionState target,
            RemoteSessionStateMachine.KillReason reason,
            boolean kill,
            boolean applied,
            long latencyMillis,
            boolean clockSkew) {
    }

    public HeartbeatDecision evaluate(SessionSnapshot snapshot, PreconditionSample sample,
                                      long sampleSeq, Instant now) {
        if (snapshot == null || now == null) {
            return new HeartbeatDecision(RemoteSessionState.ABORTED,
                    RemoteSessionStateMachine.KillReason.VISIBILITY_LOSS, true, true, 0, false);
        }
        boolean live = snapshot.state() == RemoteSessionState.ACTIVE;

        // (0) seq-independent heartbeat-timeout kill: a live session that stopped getting fresh samples
        // must die even if nothing fresh arrives (Codex absorb — no indefinitely-alive stale session).
        if (live && snapshot.lastFreshAt() != null
                && Duration.between(snapshot.lastFreshAt(), now).compareTo(maxHeartbeatAge) > 0) {
            return new HeartbeatDecision(RemoteSessionState.ABORTED,
                    RemoteSessionStateMachine.KillReason.HEARTBEAT_TIMEOUT, true, true, 0, false);
        }
        if (sample == null) {
            // malformed heartbeat for a live session → fail-closed visibility loss
            return new HeartbeatDecision(
                    live ? RemoteSessionState.ABORTED : snapshot.state(),
                    live ? RemoteSessionStateMachine.KillReason.VISIBILITY_LOSS
                            : RemoteSessionStateMachine.KillReason.NOT_ACTIVE,
                    live, true, 0, false);
        }
        // (1) monotonicity: a stale/out-of-order sample never even reads the store — and never rewinds state.
        if (!RemoteSessionStateMachine.isFreshSample(sampleSeq, snapshot.lastAppliedSeq())) {
            return new HeartbeatDecision(snapshot.state(),
                    RemoteSessionStateMachine.KillReason.NONE, false, false, 0, false);
        }
        // (2) authoritative token liveness + cert binding from ONE atomic store read (B1.1c): a partition
        // between two separate reads could misread a bound token as legacy-unbound (fail-open) — with the
        // single read, store-down surfaces as STORE_UNAVAILABLE on the token precondition and the binding
        // is only trusted when the SAME read proved the row (liveness=LIVE ⇒ null binding = truly unbound).
        TokenLifecycleStore.TokenStatus status = store.status(snapshot.jti(), now);
        TokenLifecycleStore.TokenLiveCheckResult liveResult = status.liveness();
        // (3) presented-vs-bound (B1.1c). Evaluated only for cert-sampling instruments; the token-backstop
        // reconciler (certUnsampled) cannot see the TLS layer and must not kill on a guarantee it cannot
        // observe — the live heartbeat remains the cert enforcement point.
        CertBindingGuard.Decision certDecision = sample.certSampled()
                ? CertBindingGuard.decide(status.boundThumbprint(), sample.presentedThumbprint(), certBindingPolicy)
                : null;
        boolean certBound = certDecision == null || certDecision.satisfied();
        // The full presented cert identity (B1.4a-0): ONE CertRef feeds BOTH the trust evaluator (enriched
        // with serial/issuer for the B1.4a PKIX path-build) and the identity guard. Only for cert-sampling.
        CertRef presentedCert = sample.certSampled()
                ? new CertRef(sample.presentedThumbprint(), "SHA-256",
                        sample.presentedSerialNumber(), sample.presentedIssuerDn())
                : null;
        // (3b-i) cert IDENTITY (B1.4a-0): does the presented cert match the configured agent-CA issuer pin
        // (and a future bound serial)? ADDITIVE to binding+trust, folded INTO certValid (no new precondition).
        // NOT_ENFORCED when no pin is configured (opt-in hardening — binding+trust still protect the session);
        // fail-closed when a pin IS set and the presented issuer is wrong/absent. Cert-sampling only.
        CertIdentityGuard.Decision identityDecision =
                sample.certSampled() ? CertIdentityGuard.decide(expectedCertIdentity, presentedCert) : null;
        // (3b-ii) cert TRUST (B1.2): chain + CRL/OCSP. Like binding, evaluated ONLY for cert-sampling
        // instruments — the token-backstop reconciler has no transport view, so it must not trust-kill a
        // guarantee it cannot observe; the live heartbeat is the cert enforcement point. Fail-closed: any
        // non-ALLOW verdict (incl. UNKNOWN/STALE) → certValid=false → kill.
        CertTrustEvaluator.TrustDecision trustDecision =
                sample.certSampled() ? trustEvaluator.evaluate(presentedCert, now) : null;
        // certValid folds identity + trust: BOTH must hold (an unsampled instrument leaves both null = pass).
        boolean certValid = (identityDecision == null || identityDecision.satisfied())
                && (trustDecision == null || trustDecision.isValid());
        // (3c) agent ATTESTATION (B1.3b): SLSA/builder/signed-predicate provenance. Like binding + trust,
        // evaluated ONLY for cert-sampling instruments — the token-backstop reconciler sees no presented
        // provenance and must not attestation-kill a guarantee it cannot observe (it would otherwise kill
        // every healthy bound session each poll sweep); the live heartbeat is the enforcement point.
        // Fail-closed: any non-VERIFIED verdict (missing / untrusted-or-revoked builder / policy-mismatch /
        // bad-signature) → agentAttestation=false → kill. When unsampled, the backstop's asserted last-known
        // boolean passes through (it cannot re-derive the verdict).
        // certSampled ⇒ ALWAYS a non-null decision (verifyAttestation maps a null-returning OR throwing
        // verifier to MISSING), so the live path is fail-closed regardless of the verifier impl (Codex
        // 019eb6d2 #1). The null branch is reached ONLY when unsampled — and a withCert sample pins its
        // agentAttestation boolean to false, so even a hypothetical null here on the live path cannot
        // fail-open (computed verdict takes precedence; the pinned boolean is never the source of an ALLOW).
        AttestationVerifier.AttestationDecision attestationDecision =
                sample.certSampled() ? verifyAttestation(sample.attestationEvidence(), now) : null;
        boolean agentAttestation = attestationDecision != null
                ? attestationDecision.isVerified()  // live computed verdict (cert-sampling path)
                : sample.agentAttestation();         // unsampled token-backstop pass-through (never on withCert)
        RemoteSessionPreconditions current = new RemoteSessionPreconditions(
                sample.policyAllow(), sample.targetConsent(), sample.dualApproval(),
                liveResult.isLive(), certValid, certBound, agentAttestation, sample.recordingWriterAck());
        // (4) re-evaluate.
        RemoteSessionStateMachine.Reevaluation reev = stateMachine.reevaluateActive(snapshot.state(), current);
        // refineCertIdentityReason runs INNER than refineTrustReason: both key on CERT_TRUST_LOST, and an
        // identity failure (wrong CA / no issuer) is the more fundamental "wrong cert" cause — so it consumes
        // CERT_TRUST_LOST first and the trust refiner then no-ops. When identity holds, it passes through and
        // the trust refiner reports the revocation/expiry/etc. cause.
        RemoteSessionStateMachine.KillReason reason = refineAttestationReason(
                refineTrustReason(
                        refineCertIdentityReason(
                                refineCertReason(refineTokenReason(reev.reason(), liveResult), certDecision),
                                identityDecision),
                        trustDecision),
                attestationDecision);

        // (5) latency for the SLO — clamp ≥ 0, flag clock skew rather than silently zeroing.
        long latency = 0;
        boolean skew = false;
        if (reev.isKill() && sample.revokedAt() != null) {
            long delta = Duration.between(sample.revokedAt(), now).toMillis();
            if (delta < 0) {
                skew = true; // now < revokedAt (clock skew / out-of-order) → meter revocation_clock_skew
            } else {
                latency = delta;
            }
        }
        return new HeartbeatDecision(reev.target(), reason, reev.isKill(), true, latency, skew);
    }

    /**
     * When the kill is due to token-loss ({@code TOKEN_REVOKED} from the state machine), refine it to the
     * precise cause the store reported, so audit/IR sees REVOKED vs EXPIRED vs STORE_UNAVAILABLE vs
     * NOT_FOUND rather than mislabeling a partition as a revocation. Non-token kill reasons pass through.
     */
    private static RemoteSessionStateMachine.KillReason refineTokenReason(
            RemoteSessionStateMachine.KillReason base, TokenLifecycleStore.TokenLiveCheckResult liveResult) {
        if (base != RemoteSessionStateMachine.KillReason.TOKEN_REVOKED) {
            return base; // a higher-precedence guarantee (policy/attestation/recorder/consent) was lost
        }
        return switch (liveResult) {
            case REVOKED -> RemoteSessionStateMachine.KillReason.TOKEN_REVOKED;
            case EXPIRED -> RemoteSessionStateMachine.KillReason.TOKEN_EXPIRED;
            case STORE_UNAVAILABLE -> RemoteSessionStateMachine.KillReason.STORE_UNAVAILABLE;
            case NOT_FOUND, INVALID -> RemoteSessionStateMachine.KillReason.TOKEN_NOT_FOUND;
            case LIVE -> RemoteSessionStateMachine.KillReason.TOKEN_REVOKED; // unreachable (live ⇒ no kill)
        };
    }

    /**
     * When the kill is due to the cert-binding ({@code CERT_BINDING_LOST} from the state machine), refine
     * it to the precise guard decision — MISMATCH (possible token theft) vs PRESENTED_MISSING (transport
     * lost its client cert) vs UNBOUND_REJECTED (legacy-unbound under REQUIRE_BOUND, incl. a mid-session
     * flag flip) — same audit/IR precedent as the token refinement. Non-cert reasons pass through.
     */
    private static RemoteSessionStateMachine.KillReason refineCertReason(
            RemoteSessionStateMachine.KillReason base, CertBindingGuard.Decision certDecision) {
        if (base != RemoteSessionStateMachine.KillReason.CERT_BINDING_LOST || certDecision == null) {
            return base;
        }
        return switch (certDecision) {
            case MISMATCH -> RemoteSessionStateMachine.KillReason.CERT_BINDING_MISMATCH;
            case PRESENTED_MISSING -> RemoteSessionStateMachine.KillReason.CERT_PRESENTED_MISSING;
            case UNBOUND_REJECTED -> RemoteSessionStateMachine.KillReason.CERT_UNBOUND_REJECTED;
            // satisfied decisions can't be the firstLost cause (unreachable defensive arm):
            case BOUND_MATCH, UNBOUND_ALLOWED -> RemoteSessionStateMachine.KillReason.CERT_BINDING_LOST;
        };
    }

    /**
     * When the kill is due to cert-trust ({@code CERT_TRUST_LOST} from the state machine), refine it to the
     * precise verdict — REVOKED / EXPIRED / NOT_TRUSTED / UNKNOWN / STALE — so audit/IR sees the real cause
     * (a CRL revocation vs an unreachable responder vs a stale cache vs an untrusted chain). Same precedent
     * as the token + cert-binding refinements. Non-trust reasons pass through.
     */
    private static RemoteSessionStateMachine.KillReason refineTrustReason(
            RemoteSessionStateMachine.KillReason base, CertTrustEvaluator.TrustDecision trustDecision) {
        if (base != RemoteSessionStateMachine.KillReason.CERT_TRUST_LOST || trustDecision == null) {
            return base;
        }
        return switch (trustDecision) {
            case REVOKED -> RemoteSessionStateMachine.KillReason.CERT_REVOKED;
            case EXPIRED -> RemoteSessionStateMachine.KillReason.CERT_EXPIRED;
            case NOT_TRUSTED -> RemoteSessionStateMachine.KillReason.CERT_UNTRUSTED;
            case UNKNOWN -> RemoteSessionStateMachine.KillReason.CERT_UNKNOWN;
            case STALE -> RemoteSessionStateMachine.KillReason.CERT_STALE;
            case ALLOW -> RemoteSessionStateMachine.KillReason.CERT_TRUST_LOST; // unreachable (valid ⇒ no kill)
        };
    }

    /**
     * When the kill folds in a cert-IDENTITY loss (B1.4a-0) — the certValid precondition surfaced as
     * {@code CERT_TRUST_LOST} but the cause is the issuer/serial pin, not revocation — refine it to
     * ISSUER_MISSING / ISSUER_MISMATCH (wrong CA) / SERIAL_MISMATCH. Runs INNER than
     * {@link #refineTrustReason} so a genuine identity failure outranks the trust verdict. A satisfied
     * (NOT_ENFORCED/MATCH) or {@code null} decision passes through (then the trust refiner reports its cause).
     */
    private static RemoteSessionStateMachine.KillReason refineCertIdentityReason(
            RemoteSessionStateMachine.KillReason base, CertIdentityGuard.Decision decision) {
        if (base != RemoteSessionStateMachine.KillReason.CERT_TRUST_LOST
                || decision == null || decision.satisfied()) {
            return base;
        }
        return switch (decision) {
            case ISSUER_MISSING -> RemoteSessionStateMachine.KillReason.CERT_IDENTITY_ISSUER_MISSING;
            case ISSUER_MISMATCH -> RemoteSessionStateMachine.KillReason.CERT_IDENTITY_ISSUER_MISMATCH;
            case SERIAL_MISMATCH -> RemoteSessionStateMachine.KillReason.CERT_IDENTITY_SERIAL_MISMATCH;
            // satisfied decisions are guarded above (unreachable defensive arm):
            case NOT_ENFORCED, MATCH -> base;
        };
    }

    /**
     * Run the injected verifier FAIL-CLOSED: a {@code null} return OR any thrown {@link RuntimeException}
     * (e.g. a future B1.4 Sigstore/cosign or OCSP transport error) maps to
     * {@link AttestationVerifier.AttestationDecision#MISSING}, so a cert-sampling heartbeat can NEVER
     * fail-open on an ill-behaved verifier — an unprovable build is no build (Codex 019eb6d2 #1). The
     * in-memory reference verifier is pure + total, but the real transport-backed seam must not be trusted
     * to be exception-free, and an exception bubbling out of {@code evaluate()} could otherwise leave a live
     * session un-killed.
     */
    private AttestationVerifier.AttestationDecision verifyAttestation(AttestationEvidence evidence, Instant now) {
        try {
            AttestationVerifier.AttestationDecision d = attestationVerifier.verify(evidence, now);
            return d == null ? AttestationVerifier.AttestationDecision.MISSING : d;
        } catch (RuntimeException ex) {
            return AttestationVerifier.AttestationDecision.MISSING; // a verifier that errors can't prove a build
        }
    }

    /**
     * When the kill is due to agent attestation ({@code ATTESTATION_LOST} from the state machine), refine it
     * to the precise verifier verdict — MISSING / UNTRUSTED_BUILDER / POLICY_MISMATCH / SIGNATURE_INVALID —
     * so audit/IR sees whether the agent presented no provenance, came from an untrusted (or mid-session
     * revoked) builder, failed the expected SLSA policy, or carried a bad predicate signature. Same precedent
     * as the token + cert refinements. Non-attestation reasons pass through.
     */
    private static RemoteSessionStateMachine.KillReason refineAttestationReason(
            RemoteSessionStateMachine.KillReason base, AttestationVerifier.AttestationDecision decision) {
        if (base != RemoteSessionStateMachine.KillReason.ATTESTATION_LOST || decision == null) {
            return base;
        }
        return switch (decision) {
            case MISSING -> RemoteSessionStateMachine.KillReason.ATTESTATION_MISSING;
            case UNTRUSTED_BUILDER -> RemoteSessionStateMachine.KillReason.ATTESTATION_UNTRUSTED_BUILDER;
            case POLICY_MISMATCH -> RemoteSessionStateMachine.KillReason.ATTESTATION_POLICY_MISMATCH;
            case SIGNATURE_INVALID -> RemoteSessionStateMachine.KillReason.ATTESTATION_SIG_INVALID;
            // VERIFIED can't be the firstLost cause (unreachable defensive arm):
            case VERIFIED -> RemoteSessionStateMachine.KillReason.ATTESTATION_LOST;
        };
    }
}
