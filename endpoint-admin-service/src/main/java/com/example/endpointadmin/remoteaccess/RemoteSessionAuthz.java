package com.example.endpointadmin.remoteaccess;

/**
 * Faz 22.6 {@code remote_session} authz constants + the dual-control invariant (ADR-0033 §4).
 *
 * <p>Authz lives in the same OpenFGA/Zanzibar plane as the rest of the platform. The
 * {@code remote_session} type models the grants ({@code can_request}, {@code can_approve},
 * {@code operator}, {@code target_device}, {@code capability}); however the inequality
 * <b>approver ≠ requester</b> (no self-approval) is NOT expressible at the tuple level and MUST be
 * enforced broker-side — {@link #approverDistinctFromRequester} is that server-side invariant.
 *
 * <p>NOTE: the live OpenFGA model file (gitops {@code openfga/model.fga}) is intentionally NOT
 * modified by this disabled-by-default skeleton — adding the {@code remote_session} type is a
 * desired-state change tied to runtime enablement (separate gitops PR + seed + drift-guard align).
 */
public final class RemoteSessionAuthz {

    /** OpenFGA object type for a remote session. */
    public static final String TYPE = "remote_session";

    public static final String REL_TARGET_DEVICE = "target_device";
    public static final String REL_REQUESTER = "requester";
    public static final String REL_APPROVER = "approver";
    public static final String REL_OPERATOR = "operator";
    /** Gated by endpoint-admin MODULE {@code can_manage}. */
    public static final String REL_CAN_REQUEST = "can_request";
    /** Distinct grant from {@link #REL_CAN_REQUEST}; proposer ≠ approver still enforced broker-side. */
    public static final String REL_CAN_APPROVE = "can_approve";
    public static final String REL_CAPABILITY = "capability";

    private RemoteSessionAuthz() {
    }

    /**
     * Server-side dual-control invariant (ADR-0033 §4, reusing BE-017 semantics): the approver must be
     * a different identity from the requester. Fail-closed: null/blank principals are denied. Surrounding
     * whitespace is trimmed before comparison so {@code "u1"} and {@code " u1 "} are NOT treated as
     * distinct (Codex 019eb522 REVISE absorb).
     *
     * <p><b>Scope limit:</b> this enforces "different principal id". It does NOT defend against the same
     * human acting under two distinct ids (alias / proxy / service-account). That canonical-identity
     * mapping is an IAM-layer responsibility (resolve principals to a stable subject before they reach
     * this check); case-folding is intentionally NOT done here because principal-id case-sensitivity is
     * provider-defined and folding could wrongly equate two genuinely-distinct ids.
     *
     * @return {@code true} iff approval is allowed (distinct, non-blank principals).
     */
    public static boolean approverDistinctFromRequester(String requesterUserId, String approverUserId) {
        if (requesterUserId == null || approverUserId == null) {
            return false;
        }
        String req = requesterUserId.strip();
        String appr = approverUserId.strip();
        if (req.isEmpty() || appr.isEmpty()) {
            return false;
        }
        return !req.equals(appr);
    }

    /**
     * Faz 22.6 D10-E10 (Codex 019ebe06) — the dual-control invariant enforced on CANONICAL subjects: resolve
     * BOTH the requester and approver principals (which may be aliases / proxies / service-accounts) to their
     * stable canonical subject via a {@link CanonicalIdentityResolver}, then require the canonical subjects to
     * be distinct. This closes the gap that {@link #approverDistinctFromRequester} explicitly leaves open — the
     * same human approving their own request under a second id (red-team must-land #10).
     *
     * <p><b>Fail-closed:</b> a null resolver, or EITHER principal failing to resolve to a canonical subject,
     * denies approval (an unresolvable principal can never be proven distinct from another). The final
     * distinctness check reuses {@link #approverDistinctFromRequester}, so blank/whitespace handling is
     * identical on the resolved canonical subjects.
     *
     * @return {@code true} iff both principals resolve AND their canonical subjects are distinct.
     */
    public static boolean approverDistinctFromRequesterCanonical(CanonicalIdentityResolver resolver,
                                                                 String requesterPrincipal,
                                                                 String approverPrincipal) {
        if (resolver == null) {
            return false;
        }
        String requesterCanonical;
        String approverCanonical;
        try {
            requesterCanonical = resolver.canonicalSubject(requesterPrincipal).orElse(null);
            approverCanonical = resolver.canonicalSubject(approverPrincipal).orElse(null);
        } catch (RuntimeException resolverFault) {
            // the resolver contract is total, but a misbehaving (contract-violating) resolver is fail-closed
            // here too — defense-in-depth (Codex REVISE)
            return false;
        }
        if (requesterCanonical == null || approverCanonical == null) {
            return false; // an unresolvable principal is fail-closed — distinctness cannot be proven
        }
        return approverDistinctFromRequester(requesterCanonical, approverCanonical);
    }
}
