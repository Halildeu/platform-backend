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

    /**
     * @param certBindingPolicy the legacy-unbound feature flag (B1.1c). {@code null} is coerced to
     *                          {@link CertBindingGuard.Policy#REQUIRE_BOUND} (fail-closed default).
     * @param trustEvaluator    the B1.2 cert-trust source (chain + CRL/OCSP). {@code null} is coerced to a
     *                          deny-all evaluator (fail-closed: an absent trust source can't prove trust →
     *                          NOT_TRUSTED → kill any cert-sampling session).
     */
    public RemoteSessionHeartbeat(TokenLifecycleStore store, RemoteSessionStateMachine stateMachine,
                                  Duration maxHeartbeatAge, CertBindingGuard.Policy certBindingPolicy,
                                  CertTrustEvaluator trustEvaluator) {
        this.store = store;
        this.stateMachine = stateMachine;
        this.maxHeartbeatAge = maxHeartbeatAge;
        this.certBindingPolicy =
                certBindingPolicy == null ? CertBindingGuard.Policy.REQUIRE_BOUND : certBindingPolicy;
        this.trustEvaluator = trustEvaluator == null
                ? (cert, now) -> CertTrustEvaluator.TrustDecision.NOT_TRUSTED
                : trustEvaluator;
    }

    /**
     * @param lastFreshAt when the last FRESH sample was applied (heartbeat-timeout anchor; nullable)
     */
    public record SessionSnapshot(
            String sessionId, String jti, RemoteSessionState state, long lastAppliedSeq, Instant lastFreshAt) {
    }

    /**
     * One heartbeat's runtime observation. Build via {@link #withCert} (cert-sampling instruments — the
     * live agent/tunnel heartbeat, which sees the TLS layer) or {@link #certUnsampled} (token-backstop
     * instruments — the store-driven revocation reconciler, which has NO transport view and must not
     * degrade the cert guarantee it cannot observe).
     *
     * @param certSampled         whether this sample carries a transport view (presented thumbprint). When
     *                            {@code false}, the cert-binding precondition is NOT evaluated for this
     *                            sample (pass-through) — ONLY for instruments that structurally cannot see
     *                            the TLS layer; the live heartbeat path MUST use {@link #withCert}, where a
     *                            missing presented cert on a bound token is a fail-closed kill.
     * @param presentedThumbprint the live TLS-layer client-cert SHA-256 thumbprint ({@code null} = none)
     * @param revokedAt           the t0 of a pending revocation (for SLO latency), or {@code null} if none
     */
    public record PreconditionSample(
            boolean policyAllow,
            boolean targetConsent,
            boolean dualApproval,
            boolean agentAttestation,
            boolean recordingWriterAck,
            boolean certSampled,
            String presentedThumbprint,
            Instant revokedAt) {

        /** Cert-sampling sample (the live heartbeat path) — the presented thumbprint IS enforced. */
        public static PreconditionSample withCert(
                boolean policyAllow, boolean targetConsent, boolean dualApproval,
                boolean agentAttestation, boolean recordingWriterAck,
                String presentedThumbprint, Instant revokedAt) {
            return new PreconditionSample(policyAllow, targetConsent, dualApproval,
                    agentAttestation, recordingWriterAck, true, presentedThumbprint, revokedAt);
        }

        /** Token-backstop sample (no transport view, e.g. the revocation reconciler) — cert pass-through. */
        public static PreconditionSample certUnsampled(
                boolean policyAllow, boolean targetConsent, boolean dualApproval,
                boolean agentAttestation, boolean recordingWriterAck, Instant revokedAt) {
            return new PreconditionSample(policyAllow, targetConsent, dualApproval,
                    agentAttestation, recordingWriterAck, false, null, revokedAt);
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
        // (3b) cert TRUST (B1.2): chain + CRL/OCSP. Like binding, evaluated ONLY for cert-sampling
        // instruments — the token-backstop reconciler has no transport view, so it must not trust-kill a
        // guarantee it cannot observe; the live heartbeat is the cert enforcement point. Fail-closed: any
        // non-ALLOW verdict (incl. UNKNOWN/STALE) → certValid=false → kill.
        CertTrustEvaluator.TrustDecision trustDecision = sample.certSampled()
                ? trustEvaluator.evaluate(CertRef.ofThumbprint(sample.presentedThumbprint()), now)
                : null;
        boolean certValid = trustDecision == null || trustDecision.isValid();
        RemoteSessionPreconditions current = new RemoteSessionPreconditions(
                sample.policyAllow(), sample.targetConsent(), sample.dualApproval(),
                liveResult.isLive(), certValid, certBound, sample.agentAttestation(), sample.recordingWriterAck());
        // (4) re-evaluate.
        RemoteSessionStateMachine.Reevaluation reev = stateMachine.reevaluateActive(snapshot.state(), current);
        RemoteSessionStateMachine.KillReason reason = refineTrustReason(
                refineCertReason(refineTokenReason(reev.reason(), liveResult), certDecision), trustDecision);

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
}
