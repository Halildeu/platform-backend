package com.example.endpointadmin.remoteaccess;

import java.util.Objects;

/**
 * Faz 22.6 D10-E10 (Codex 019ebe06) — the single fail-closed DECISION point for a dual-control session-request
 * approval (ADR-0033 §4 + ADR-0034 §11 must-land #10). It composes the E10 primitives into ONE chokepoint so
 * the eventual approval-flow has a single, audited, testable place that decides whether an approval may
 * proceed — not scattered checks:
 * <ol>
 *   <li>both principals resolve to a canonical subject ({@link CanonicalIdentityResolver}); else DENY;</li>
 *   <li>the requester holds {@code can_request} AND the approver holds {@code can_approve} (the authz-plane
 *       grants — passed in as booleans because the live OpenFGA {@code remote_session} model is owner-gated;
 *       the caller checks the tuples); else DENY;</li>
 *   <li>approver ≠ requester on the CANONICAL subject ({@link RemoteSessionAuthz}); else DENY (self-approval,
 *       incl. via alias/proxy/service-account);</li>
 *   <li>the approver is under the per-window fatigue cap ({@link ApprovalFatigueLimiter}); else DENY.</li>
 * </ol>
 *
 * <p><b>Fail-closed + order:</b> identity + grants + distinctness are checked BEFORE the fatigue limiter, so a
 * denied approval (bad identity / missing grant / self-approval) does NOT consume the approver's fatigue
 * budget — only an otherwise-valid approval records against the cap. Any null collaborator denies. The outcome
 * carries a coarse reason for audit; the CALLER must not leak the distinct reasons to a client as an oracle
 * (Codex) — externally a refusal is a refusal.
 */
public final class RemoteSessionApprovalGate {

    /** Why an approval was allowed or refused — internal audit detail, never an external oracle. */
    public enum Outcome {
        ALLOWED,
        DENIED_UNRESOLVED_IDENTITY,
        DENIED_MISSING_GRANT,
        DENIED_SELF_APPROVAL,
        DENIED_FATIGUED;

        public boolean isAllowed() {
            return this == ALLOWED;
        }
    }

    private final CanonicalIdentityResolver resolver;
    private final ApprovalFatigueLimiter fatigueLimiter;

    public RemoteSessionApprovalGate(CanonicalIdentityResolver resolver, ApprovalFatigueLimiter fatigueLimiter) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.fatigueLimiter = Objects.requireNonNull(fatigueLimiter, "fatigueLimiter");
    }

    /**
     * Decide a dual-control approval. {@code requesterHasCanRequest} / {@code approverHasCanApprove} are the
     * authz-plane grant results the caller computed (the live OpenFGA check is owner-gated). An ALLOWED outcome
     * has recorded the approver against the fatigue cap; a denied outcome records nothing.
     */
    public Outcome decide(String requesterPrincipal, String approverPrincipal,
                          boolean requesterHasCanRequest, boolean approverHasCanApprove, long nowEpochMillis) {
        String requesterCanonical;
        String approverCanonical;
        try {
            requesterCanonical = resolver.canonicalSubject(requesterPrincipal).orElse(null);
            approverCanonical = resolver.canonicalSubject(approverPrincipal).orElse(null);
        } catch (RuntimeException resolverFault) {
            // this gate is THE single dual-control chokepoint — a contract-violating (throwing) resolver is
            // fail-closed here too, mirroring RemoteSessionAuthz's caller-defense (Codex REVISE)
            return Outcome.DENIED_UNRESOLVED_IDENTITY;
        }
        if (requesterCanonical == null || approverCanonical == null) {
            return Outcome.DENIED_UNRESOLVED_IDENTITY; // an unidentifiable party can never pass dual-control
        }
        if (!requesterHasCanRequest || !approverHasCanApprove) {
            return Outcome.DENIED_MISSING_GRANT;
        }
        if (!RemoteSessionAuthz.approverDistinctFromRequester(requesterCanonical, approverCanonical)) {
            return Outcome.DENIED_SELF_APPROVAL; // same canonical subject (incl. alias/proxy/service-account)
        }
        // fatigue is checked + recorded LAST, so a denial above never spends the approver's budget
        if (fatigueLimiter.recordApproval(approverCanonical, nowEpochMillis) != ApprovalFatigueLimiter.Decision.ALLOWED) {
            return Outcome.DENIED_FATIGUED;
        }
        return Outcome.ALLOWED;
    }
}
