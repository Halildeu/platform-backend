package com.example.gpcore.authz;

import com.example.gpcore.domain.Action;
import com.example.gpcore.domain.AuthorizationDecision;
import com.example.gpcore.domain.Edge;
import com.example.gpcore.domain.NodePolicy;
import com.example.gpcore.domain.NodeRef;
import com.example.gpcore.domain.Principal;
import com.example.gpcore.domain.SubjectAttributes;
import com.example.gpcore.port.NodePolicyPort;
import com.example.gpcore.port.PolicyVersionProvider;
import com.example.gpcore.port.RelationshipChecker;
import com.example.gpcore.port.SubjectAttributePort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * THE single authorization choke-point (ADR-0035). Every read path in the Read
 * Gateway resolves its decision here; OpenFGA is the data-plane policy
 * enforcement point (relationship plane) and a deny-overrides ABAC layer sits on
 * top.
 *
 * <p>Decision pipeline for {@code decideAction(p, ref, action)}:
 * <ol>
 *   <li>resolve version stamps (failure → deny, uncached — no safe key);</li>
 *   <li>resolve authoritative subject attributes (missing/failure → deny, uncached);</li>
 *   <li>look up / compute under a version-aware {@link AuthzCacheKey}:
 *     <ol type="a">
 *       <li>{@code RelationshipChecker.canRelate(actionRelation(action))} — a
 *           POSITIVE grant is required ({@code viewer} does not imply export);</li>
 *       <li>authoritative {@code NodePolicyPort.policyOf} (missing/failure → deny);</li>
 *       <li>deny-overrides ABAC — any deny overrides the OpenFGA allow.</li>
 *     </ol>
 *   </li>
 * </ol>
 *
 * <p>Every uncertainty is fail-closed (deny). The cache only ever stores a
 * computed allow/deny under a key that carries policy/tuple/subject versions, so
 * a positive can never survive a revocation (no stale-positive leak — the
 * correctness layer lives here, not in any version-unaware downstream cache).
 */
public class AuthorizationDecisionService {

    private final RelationshipChecker checker;
    private final NodePolicyPort policyPort;
    private final SubjectAttributePort subjectPort;
    private final PolicyVersionProvider versions;
    private final ActionRelationPolicy actionRelation;
    private final PolicyDenyEvaluator denyEvaluator;
    private final DecisionCache cache;
    private final Set<String> globalEdgeAllowlist;
    private final String storeId;
    private final String modelId;

    public AuthorizationDecisionService(RelationshipChecker checker,
                                        NodePolicyPort policyPort,
                                        SubjectAttributePort subjectPort,
                                        PolicyVersionProvider versions,
                                        ActionRelationPolicy actionRelation,
                                        PolicyDenyEvaluator denyEvaluator,
                                        DecisionCache cache,
                                        Set<String> globalEdgeAllowlist,
                                        String storeId,
                                        String modelId) {
        this.checker = checker;
        this.policyPort = policyPort;
        this.subjectPort = subjectPort;
        this.versions = versions;
        this.actionRelation = actionRelation;
        this.denyEvaluator = denyEvaluator;
        this.cache = cache;
        this.globalEdgeAllowlist = Set.copyOf(globalEdgeAllowlist);
        this.storeId = storeId;
        this.modelId = modelId;
    }

    public AuthorizationDecision decideView(Principal principal, NodeRef ref) {
        return decideAction(principal, ref, Action.VIEW);
    }

    /** Single decision for {@code action} on {@code ref}. Fail-closed throughout. */
    public AuthorizationDecision decideAction(Principal principal, NodeRef ref, Action action) {
        VersionStamp v = resolveVersions();
        if (v == null) {
            return AuthorizationDecision.deny("version_unavailable");
        }
        SubjectAttributes subject = resolveSubject(principal);
        if (subject == null) {
            return AuthorizationDecision.deny("subject_missing");
        }
        String relation = actionRelation.relationFor(action);
        AuthzCacheKey key = key(principal, subject, relation, action, ref, v);
        return cache.get(key, () -> compute(principal, subject, ref, action, relation));
    }

    /**
     * Bounded bulk authorization for {@code VIEW} (ADR-0035 §6). Cache hits are
     * served directly; misses are resolved through a single batched relationship
     * check, then per-node policy + ABAC. A partial / short / malformed batch
     * response or a checker exception denies the affected item (fail-closed per
     * item — Codex 019f1913 #7). Results are positionally aligned with {@code refs}.
     */
    public List<AuthorizationDecision> decideViewBatch(Principal principal, List<NodeRef> refs) {
        final Action action = Action.VIEW;
        final String relation = actionRelation.relationFor(action);
        AuthorizationDecision[] out = new AuthorizationDecision[refs.size()];

        VersionStamp v = resolveVersions();
        if (v == null) {
            Arrays.fill(out, AuthorizationDecision.deny("version_unavailable"));
            return Arrays.asList(out);
        }
        SubjectAttributes subject = resolveSubject(principal);
        if (subject == null) {
            Arrays.fill(out, AuthorizationDecision.deny("subject_missing"));
            return Arrays.asList(out);
        }

        List<Integer> missIdx = new ArrayList<>();
        List<RelationshipChecker.RelationRequest> missReq = new ArrayList<>();
        for (int i = 0; i < refs.size(); i++) {
            AuthzCacheKey key = key(principal, subject, relation, action, refs.get(i), v);
            AuthorizationDecision cached = cache.getIfPresent(key);
            if (cached != null) {
                out[i] = cached;
            } else {
                missIdx.add(i);
                missReq.add(new RelationshipChecker.RelationRequest(relation, refs.get(i)));
            }
        }

        if (!missReq.isEmpty()) {
            List<Boolean> related;
            try {
                related = checker.canRelateBatch(principal, missReq);
            } catch (RuntimeException e) {
                related = null; // whole batch failed → each missing item denies below
            }
            for (int j = 0; j < missIdx.size(); j++) {
                int i = missIdx.get(j);
                NodeRef ref = refs.get(i);
                Boolean grant = (related != null && j < related.size()) ? related.get(j) : null;
                AuthorizationDecision d;
                if (grant == null) {
                    d = AuthorizationDecision.deny("relationship_batch_missing");
                } else if (!grant) {
                    d = AuthorizationDecision.deny("no_relation");
                } else {
                    d = applyPolicyAndAbac(principal, subject, ref, action);
                }
                cache.put(key(principal, subject, relation, action, ref, v), d);
                out[i] = d;
            }
        }
        return Arrays.asList(out);
    }

    /**
     * Edge visibility (ADR-0035 §2): visible iff source AND target AND edge_scope
     * are all viewable. A {@code null} scope is permitted ONLY for an allowlisted
     * global edge type; otherwise a scoped edge with no scope is fail-closed
     * (Codex 019f1913 #6). The edge's own policy is carried by its scope node, so
     * an edge between two visible endpoints is still hidden when the scope's
     * policy/relationship denies.
     */
    public AuthorizationDecision decideEdge(Principal principal, Edge edge) {
        if (decideView(principal, edge.source()).denied()) {
            return AuthorizationDecision.deny("edge:source_hidden");
        }
        if (decideView(principal, edge.target()).denied()) {
            return AuthorizationDecision.deny("edge:target_hidden");
        }
        if (edge.scope() == null) {
            if (!globalEdgeAllowlist.contains(edge.edgeType())) {
                return AuthorizationDecision.deny("edge:null_scope_not_global");
            }
        } else if (decideView(principal, edge.scope()).denied()) {
            return AuthorizationDecision.deny("edge:scope_hidden");
        }
        return AuthorizationDecision.allow();
    }

    // --- internals ---

    private AuthorizationDecision compute(Principal principal, SubjectAttributes subject,
                                          NodeRef ref, Action action, String relation) {
        boolean related;
        try {
            related = checker.canRelate(principal, relation, ref);
        } catch (RuntimeException e) {
            return AuthorizationDecision.deny("relationship_error");
        }
        if (!related) {
            return AuthorizationDecision.deny("no_relation");
        }
        return applyPolicyAndAbac(principal, subject, ref, action);
    }

    /** Policy lookup + deny-overrides ABAC tail (assumes the positive relationship already holds). */
    private AuthorizationDecision applyPolicyAndAbac(Principal principal, SubjectAttributes subject,
                                                     NodeRef ref, Action action) {
        NodePolicy policy;
        try {
            policy = policyPort.policyOf(ref).orElse(null);
        } catch (RuntimeException e) {
            return AuthorizationDecision.deny("policy_error");
        }
        if (policy == null) {
            return AuthorizationDecision.deny("policy_missing");
        }
        Optional<String> deny;
        try {
            deny = denyEvaluator.evaluateDeny(principal, subject, ref, policy, action);
        } catch (RuntimeException e) {
            return AuthorizationDecision.deny("abac_error");
        }
        return deny.map(AuthorizationDecision::deny).orElseGet(AuthorizationDecision::allow);
    }

    private VersionStamp resolveVersions() {
        try {
            String pv = versions.policyVersion();
            String tr = versions.tupleRevision();
            if (pv == null || tr == null) {
                return null;
            }
            return new VersionStamp(pv, tr);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private SubjectAttributes resolveSubject(Principal principal) {
        try {
            SubjectAttributes s = subjectPort.resolve(principal).orElse(null);
            if (s == null || s.subjectPolicyVersion() == null) {
                return null;
            }
            return s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private AuthzCacheKey key(Principal principal, SubjectAttributes subject, String relation,
                              Action action, NodeRef ref, VersionStamp v) {
        return new AuthzCacheKey(principal.userId(), subject.subjectPolicyVersion(), relation, action,
                ref.type(), ref.id(), v.policyVersion(), v.tupleRevision(), storeId, modelId);
    }

    private record VersionStamp(String policyVersion, String tupleRevision) {}
}
