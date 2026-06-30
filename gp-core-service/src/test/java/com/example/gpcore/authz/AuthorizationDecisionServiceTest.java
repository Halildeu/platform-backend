package com.example.gpcore.authz;

import com.example.gpcore.domain.Action;
import com.example.gpcore.domain.AuthorizationDecision;
import com.example.gpcore.domain.Classification;
import com.example.gpcore.domain.Edge;
import com.example.gpcore.domain.NodePolicy;
import com.example.gpcore.domain.NodeRef;
import com.example.gpcore.domain.Principal;
import com.example.gpcore.domain.SubjectAttributes;
import com.example.gpcore.testsupport.Authz;
import com.example.gpcore.testsupport.FakeNodePolicyPort;
import com.example.gpcore.testsupport.FakePolicyVersionProvider;
import com.example.gpcore.testsupport.FakeRelationshipChecker;
import com.example.gpcore.testsupport.FakeSubjectAttributePort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.example.gpcore.authz.DefaultActionRelationPolicy.EDITOR;
import static com.example.gpcore.authz.DefaultActionRelationPolicy.VIEWER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decision-core enforcement (ADR-0035 §3/§6). Covers deny-overrides, the
 * action→relation boundary (viewer ≠ export), fail-closed paths, edge-visibility,
 * version-aware cache invalidation (no stale-positive), and bounded-batch
 * fail-closed semantics.
 */
class AuthorizationDecisionServiceTest {

    private final Principal alice = Principal.of("alice");
    private final NodeRef node = NodeRef.of("control_instance", "1");

    // --- deny-overrides ---

    @Test
    void viewerGrant_viewAllowed() {
        var checker = new FakeRelationshipChecker().grant("alice", VIEWER, node);
        var svc = Authz.service(checker, new FakeNodePolicyPort(),
                new FakeSubjectAttributePort(), new FakePolicyVersionProvider());
        assertTrue(svc.decideView(alice, node).allowed());
    }

    @Test
    void denyOverrides_legalHoldBlocksExportAndDownloadButNotView() {
        var checker = new FakeRelationshipChecker()
                .grant("alice", VIEWER, node).grant("alice", EDITOR, node);
        var policy = new FakeNodePolicyPort()
                .put(node, new NodePolicy(Classification.PUBLIC, true, Set.of()));
        var svc = Authz.service(checker, policy, new FakeSubjectAttributePort(), new FakePolicyVersionProvider());

        assertTrue(svc.decideAction(alice, node, Action.VIEW).allowed(), "view allowed under legal hold");
        assertTrue(svc.decideAction(alice, node, Action.RAG_READ).allowed(), "rag allowed under legal hold");
        assertDenied(svc.decideAction(alice, node, Action.EXPORT), "abac:legal_hold");
        assertDenied(svc.decideAction(alice, node, Action.DOWNLOAD), "abac:legal_hold");
    }

    @Test
    void denyOverrides_specialCategoryRagRequiresClearance() {
        var checker = new FakeRelationshipChecker().grant("alice", VIEWER, node);
        var policy = new FakeNodePolicyPort().put(node, NodePolicy.of(Classification.SPECIAL_CATEGORY));

        // No clearance → RAG denied, but VIEW still allowed.
        var noClearance = Authz.service(checker, policy, new FakeSubjectAttributePort(), new FakePolicyVersionProvider());
        assertTrue(noClearance.decideAction(alice, node, Action.VIEW).allowed());
        assertDenied(noClearance.decideAction(alice, node, Action.RAG_READ), "abac:clearance_required:special_category");

        // With clearance → RAG allowed.
        var withClearance = Authz.service(checker, policy,
                new FakeSubjectAttributePort().withClearance("alice", "clearance:special_category", "s1"),
                new FakePolicyVersionProvider());
        assertTrue(withClearance.decideAction(alice, node, Action.RAG_READ).allowed());
    }

    @Test
    void denyOverrides_policyTagDeniesActionEvenWithRelation() {
        var checker = new FakeRelationshipChecker()
                .grant("alice", VIEWER, node).grant("alice", EDITOR, node);
        var policy = new FakeNodePolicyPort()
                .put(node, new NodePolicy(Classification.PUBLIC, false, Set.of("deny:export")));
        var svc = Authz.service(checker, policy, new FakeSubjectAttributePort(), new FakePolicyVersionProvider());

        assertTrue(svc.decideAction(alice, node, Action.VIEW).allowed());
        assertDenied(svc.decideAction(alice, node, Action.EXPORT), "abac:policy_tag:deny:export");
    }

    // --- action→relation boundary: viewer does NOT imply export/download ---

    @Test
    void actionRelation_viewerDoesNotImplyExportOrDownload() {
        var checker = new FakeRelationshipChecker().grant("alice", VIEWER, node); // viewer only, NOT editor
        var svc = Authz.service(checker, new FakeNodePolicyPort(),
                new FakeSubjectAttributePort(), new FakePolicyVersionProvider());

        assertTrue(svc.decideAction(alice, node, Action.VIEW).allowed());
        assertTrue(svc.decideAction(alice, node, Action.RAG_READ).allowed(), "rag maps to viewer");
        assertDenied(svc.decideAction(alice, node, Action.EXPORT), "no_relation");
        assertDenied(svc.decideAction(alice, node, Action.DOWNLOAD), "no_relation");
    }

    // --- fail-closed paths ---

    @Test
    void failClosed_checkerException_denies() {
        var checker = new FakeRelationshipChecker().grant("alice", VIEWER, node).throwOnCheck(true);
        var svc = Authz.service(checker, new FakeNodePolicyPort(),
                new FakeSubjectAttributePort(), new FakePolicyVersionProvider());
        assertDenied(svc.decideView(alice, node), "relationship_error");
    }

    @Test
    void failClosed_policyMissing_denies() {
        var checker = new FakeRelationshipChecker().grant("alice", VIEWER, node);
        var policy = new FakeNodePolicyPort().missingFor(node);
        var svc = Authz.service(checker, policy, new FakeSubjectAttributePort(), new FakePolicyVersionProvider());
        assertDenied(svc.decideView(alice, node), "policy_missing");
    }

    @Test
    void failClosed_policyException_denies() {
        var checker = new FakeRelationshipChecker().grant("alice", VIEWER, node);
        var policy = new FakeNodePolicyPort().throwFor(node);
        var svc = Authz.service(checker, policy, new FakeSubjectAttributePort(), new FakePolicyVersionProvider());
        assertDenied(svc.decideView(alice, node), "policy_error");
    }

    @Test
    void failClosed_versionUnavailable_denies() {
        var checker = new FakeRelationshipChecker().grant("alice", VIEWER, node);
        var svcNull = Authz.service(checker, new FakeNodePolicyPort(),
                new FakeSubjectAttributePort(), new FakePolicyVersionProvider().nullValues(true));
        assertDenied(svcNull.decideView(alice, node), "version_unavailable");

        var svcThrow = Authz.service(checker, new FakeNodePolicyPort(),
                new FakeSubjectAttributePort(), new FakePolicyVersionProvider().throwOnAccess(true));
        assertDenied(svcThrow.decideView(alice, node), "version_unavailable");
    }

    @Test
    void failClosed_subjectMissing_denies() {
        var checker = new FakeRelationshipChecker().grant("alice", VIEWER, node);
        var subject = new FakeSubjectAttributePort().unknown("alice");
        var svc = Authz.service(checker, new FakeNodePolicyPort(), subject, new FakePolicyVersionProvider());
        assertDenied(svc.decideView(alice, node), "subject_missing");
    }

    // --- cache + version-aware invalidation (no stale-positive) ---

    @Test
    void cache_servesRepeatedDecisionWithoutReinvokingChecker() {
        var checker = new FakeRelationshipChecker().grant("alice", VIEWER, node);
        var svc = Authz.service(checker, new FakeNodePolicyPort(),
                new FakeSubjectAttributePort(), new FakePolicyVersionProvider());
        assertTrue(svc.decideView(alice, node).allowed());
        assertTrue(svc.decideView(alice, node).allowed());
        assertEquals(1, checker.callCount(), "second decision served from cache");
    }

    @Test
    void stalePositive_tupleRevisionBump_reinvokesCheckerAndDenies() {
        var checker = new FakeRelationshipChecker().grant("alice", VIEWER, node);
        var versions = new FakePolicyVersionProvider().tupleRevision("tr1");
        var svc = Authz.service(checker, new FakeNodePolicyPort(), new FakeSubjectAttributePort(), versions);

        assertTrue(svc.decideView(alice, node).allowed());
        assertEquals(1, checker.callCount());

        // Tuple revoked AND revision bumped: the cached positive must NOT survive.
        checker.revoke("alice", VIEWER, node);
        versions.tupleRevision("tr2");

        AuthorizationDecision after = svc.decideView(alice, node);
        assertFalse(after.allowed(), "no stale positive after tuple-revision bump");
        assertEquals(2, checker.callCount(), "checker actually re-invoked (cache miss), not served stale");
    }

    @Test
    void stalePositive_policyVersionBump_reinvokesChecker() {
        var checker = new FakeRelationshipChecker().grant("alice", VIEWER, node);
        var policy = new FakeNodePolicyPort(); // PUBLIC default
        var versions = new FakePolicyVersionProvider().policyVersion("pv1");
        var svc = Authz.service(checker, policy, new FakeSubjectAttributePort(), versions);

        assertTrue(svc.decideView(alice, node).allowed());
        assertEquals(1, checker.callCount());

        // Policy tightened + version bumped: must re-evaluate, not serve cached allow.
        policy.put(node, new NodePolicy(Classification.PUBLIC, false, Set.of("deny:view")));
        versions.policyVersion("pv2");

        assertDenied(svc.decideView(alice, node), "abac:policy_tag:deny:view");
        assertEquals(2, checker.callCount(), "policy-version bump invalidated the cache");
    }

    @Test
    void clearanceRevocation_subjectVersionBump_reinvokesChecker() {
        var checker = new FakeRelationshipChecker().grant("alice", VIEWER, node);
        var policy = new FakeNodePolicyPort().put(node, NodePolicy.of(Classification.SPECIAL_CATEGORY));
        var subject = new FakeSubjectAttributePort()
                .withClearance("alice", "clearance:special_category", "s1");
        var svc = Authz.service(checker, policy, subject, new FakePolicyVersionProvider());

        assertTrue(svc.decideAction(alice, node, Action.RAG_READ).allowed());
        assertEquals(1, checker.callCount());

        // Clearance revoked + subject-policy version bumped.
        subject.put("alice", new SubjectAttributes(Set.of(), "s2"));
        assertDenied(svc.decideAction(alice, node, Action.RAG_READ), "abac:clearance_required:special_category");
        assertEquals(2, checker.callCount(), "subject-version bump invalidated the cache");
    }

    // --- edge visibility (ADR-0035 §2) ---

    private static final NodeRef S = NodeRef.of("control_instance", "10");
    private static final NodeRef T = NodeRef.of("risk", "20");
    private static final NodeRef SC = NodeRef.of("unit", "5");

    private AuthorizationDecisionService edgeService(FakeRelationshipChecker checker, Set<String> allowlist) {
        return Authz.service(checker, new FakeNodePolicyPort(), new FakeSubjectAttributePort(),
                new FakePolicyVersionProvider(), Authz.enabledCache(), allowlist);
    }

    @Test
    void decideEdge_allEndpointsAndScopeVisible_allow() {
        var checker = new FakeRelationshipChecker()
                .grant("alice", VIEWER, S).grant("alice", VIEWER, T).grant("alice", VIEWER, SC);
        var svc = edgeService(checker, Set.of());
        assertTrue(svc.decideEdge(alice, new Edge("contains", S, T, SC)).allowed());
    }

    @Test
    void decideEdge_sourceHidden_deny() {
        var checker = new FakeRelationshipChecker().grant("alice", VIEWER, T).grant("alice", VIEWER, SC);
        var svc = edgeService(checker, Set.of());
        assertDenied(svc.decideEdge(alice, new Edge("contains", S, T, SC)), "edge:source_hidden");
    }

    @Test
    void decideEdge_targetHidden_deny() {
        var checker = new FakeRelationshipChecker().grant("alice", VIEWER, S).grant("alice", VIEWER, SC);
        var svc = edgeService(checker, Set.of());
        assertDenied(svc.decideEdge(alice, new Edge("contains", S, T, SC)), "edge:target_hidden");
    }

    @Test
    void decideEdge_scopeHidden_deny() {
        var checker = new FakeRelationshipChecker().grant("alice", VIEWER, S).grant("alice", VIEWER, T);
        var svc = edgeService(checker, Set.of());
        assertDenied(svc.decideEdge(alice, new Edge("contains", S, T, SC)), "edge:scope_hidden");
    }

    @Test
    void decideEdge_nullScopeNotAllowlisted_failClosed() {
        var checker = new FakeRelationshipChecker().grant("alice", VIEWER, S).grant("alice", VIEWER, T);
        var svc = edgeService(checker, Set.of()); // empty allowlist
        assertDenied(svc.decideEdge(alice, new Edge("custom_edge", S, T, null)), "edge:null_scope_not_global");
    }

    @Test
    void decideEdge_nullScopeAllowlisted_allow() {
        var checker = new FakeRelationshipChecker().grant("alice", VIEWER, S).grant("alice", VIEWER, T);
        var svc = edgeService(checker, Set.of("belongs_to"));
        assertTrue(svc.decideEdge(alice, new Edge("belongs_to", S, T, null)).allowed());
    }

    // --- bounded batch: fail-closed per item ---

    @Test
    void batch_alignedResults() {
        var n1 = NodeRef.of("task", "1");
        var n2 = NodeRef.of("task", "2");
        var n3 = NodeRef.of("task", "3");
        var checker = new FakeRelationshipChecker().grant("alice", VIEWER, n1).grant("alice", VIEWER, n3);
        var svc = Authz.service(checker, new FakeNodePolicyPort(),
                new FakeSubjectAttributePort(), new FakePolicyVersionProvider());

        List<AuthorizationDecision> out = svc.decideViewBatch(alice, List.of(n1, n2, n3));
        assertTrue(out.get(0).allowed());
        assertFalse(out.get(1).allowed());
        assertTrue(out.get(2).allowed());
    }

    @Test
    void batch_shortResponse_missingItemDeniedFailClosed() {
        var n1 = NodeRef.of("task", "1");
        var n2 = NodeRef.of("task", "2");
        var n3 = NodeRef.of("task", "3");
        var checker = new FakeRelationshipChecker()
                .grant("alice", VIEWER, n1).grant("alice", VIEWER, n2).grant("alice", VIEWER, n3)
                .shortBatch(true); // drops the last positional result
        var svc = Authz.service(checker, new FakeNodePolicyPort(),
                new FakeSubjectAttributePort(), new FakePolicyVersionProvider());

        List<AuthorizationDecision> out = svc.decideViewBatch(alice, List.of(n1, n2, n3));
        assertTrue(out.get(0).allowed());
        assertTrue(out.get(1).allowed());
        assertDenied(out.get(2), "relationship_batch_missing");
    }

    @Test
    void batch_exception_allMissingItemsDeniedFailClosed() {
        var n1 = NodeRef.of("task", "1");
        var n2 = NodeRef.of("task", "2");
        var checker = new FakeRelationshipChecker()
                .grant("alice", VIEWER, n1).grant("alice", VIEWER, n2).throwOnBatch(true);
        var svc = Authz.service(checker, new FakeNodePolicyPort(),
                new FakeSubjectAttributePort(), new FakePolicyVersionProvider());

        List<AuthorizationDecision> out = svc.decideViewBatch(alice, List.of(n1, n2));
        assertDenied(out.get(0), "relationship_batch_missing");
        assertDenied(out.get(1), "relationship_batch_missing");
    }

    private static void assertDenied(AuthorizationDecision decision, String expectedReason) {
        assertFalse(decision.allowed(), "expected deny");
        assertEquals(expectedReason, decision.reason());
    }
}
