package com.example.gpcore.gateway;

import com.example.gpcore.domain.Classification;
import com.example.gpcore.domain.Edge;
import com.example.gpcore.domain.NodePolicy;
import com.example.gpcore.domain.NodeRef;
import com.example.gpcore.domain.Principal;
import com.example.gpcore.gateway.model.AuditBundle;
import com.example.gpcore.gateway.model.AuditItem;
import com.example.gpcore.gateway.model.EdgeQuery;
import com.example.gpcore.gateway.model.EvidenceContent;
import com.example.gpcore.gateway.model.EvidenceMetadataView;
import com.example.gpcore.gateway.model.GraphView;
import com.example.gpcore.gateway.model.RagChunk;
import com.example.gpcore.gateway.model.SearchQuery;
import com.example.gpcore.gateway.model.SearchResult;
import com.example.gpcore.gateway.model.TraverseRequest;
import com.example.gpcore.testsupport.Authz;
import com.example.gpcore.testsupport.FakeAuditBundlePort;
import com.example.gpcore.testsupport.FakeEvidenceReadPort;
import com.example.gpcore.testsupport.FakeNodePolicyPort;
import com.example.gpcore.testsupport.FakePolicyVersionProvider;
import com.example.gpcore.testsupport.FakeRagChunkPort;
import com.example.gpcore.testsupport.FakeRelationshipChecker;
import com.example.gpcore.testsupport.FakeSubjectAttributePort;
import com.example.gpcore.testsupport.InMemoryGraphReadPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.example.gpcore.authz.DefaultActionRelationPolicy.EDITOR;
import static com.example.gpcore.authz.DefaultActionRelationPolicy.VIEWER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mandatory Read Gateway enforcement suite (ADR-0035 §8 / build-order §1):
 * edge-visibility, no hidden-count, RAG auth-before-text, export-no-bypass,
 * no cross-user cache leak, deny-overrides — plus the Cross-AI hardening cases
 * (authoritative RAG rehydrate, forged chunk id, denied-path ports-not-called,
 * caller-action immutability, side-channel-free pagination, bounded traversal).
 */
class ReadGatewayEnforcementTest {

    private final Principal alice = Principal.of("alice");
    private final Principal bob = Principal.of("bob");

    /** Test rig: production decision service + production gateway over in-memory fakes. */
    private static final class Gw {
        final FakeRelationshipChecker checker = new FakeRelationshipChecker();
        final FakeNodePolicyPort policy = new FakeNodePolicyPort();
        final FakeSubjectAttributePort subject = new FakeSubjectAttributePort();
        final InMemoryGraphReadPort graph = new InMemoryGraphReadPort();
        final FakeEvidenceReadPort evidence = new FakeEvidenceReadPort();
        final FakeRagChunkPort rag = new FakeRagChunkPort();
        final FakeAuditBundlePort audit = new FakeAuditBundlePort();
        Set<String> allowlist = Set.of();

        ReadGateway build() {
            var decisions = Authz.service(checker, policy, subject, new FakePolicyVersionProvider(),
                    Authz.enabledCache(), allowlist);
            return new ReadGatewayImpl(decisions, graph, evidence, rag, audit, 10_000);
        }
    }

    // ---------- 1. Edge visibility: source AND target AND edge_scope ----------

    @Test
    void edgeVisibility_sourceVisible_targetHidden_noEdge() {
        var gw = new Gw();
        NodeRef s = NodeRef.of("control_instance", "1");
        NodeRef t = NodeRef.of("risk", "2");
        NodeRef scope = NodeRef.of("unit", "9");
        gw.graph.node(s).node(t).edge(new Edge("has_risk", s, t, scope));
        gw.checker.grant("alice", VIEWER, s).grant("alice", VIEWER, scope); // target NOT viewable
        ReadGateway gateway = gw.build();

        GraphView view = gateway.getEdges(alice, s, EdgeQuery.all());
        assertEquals(0, view.edges().size(), "edge hidden when target hidden");
        assertFalse(view.nodes().stream().anyMatch(n -> n.ref().equals(t)), "hidden target not surfaced");
    }

    @Test
    void edgeVisibility_targetVisible_sourceHidden_noEdge() {
        var gw = new Gw();
        NodeRef s = NodeRef.of("control_instance", "1");
        NodeRef t = NodeRef.of("risk", "2");
        NodeRef scope = NodeRef.of("unit", "9");
        gw.graph.node(s).node(t).edge(new Edge("has_risk", s, t, scope));
        gw.checker.grant("alice", VIEWER, t).grant("alice", VIEWER, scope); // source NOT viewable
        ReadGateway gateway = gw.build();

        // Query from the visible endpoint (t); the edge must still be pruned because source is hidden.
        GraphView view = gateway.getEdges(alice, t, EdgeQuery.all());
        assertEquals(0, view.edges().size(), "edge hidden when source hidden");
    }

    @Test
    void edgeVisibility_edgeScopeHidden_noEdge() {
        var gw = new Gw();
        NodeRef s = NodeRef.of("control_instance", "1");
        NodeRef t = NodeRef.of("risk", "2");
        NodeRef scope = NodeRef.of("unit", "9");
        gw.graph.node(s).node(t).edge(new Edge("has_risk", s, t, scope));
        gw.checker.grant("alice", VIEWER, s).grant("alice", VIEWER, t); // scope NOT viewable
        ReadGateway gateway = gw.build();

        GraphView view = gateway.getEdges(alice, s, EdgeQuery.all());
        assertEquals(0, view.edges().size(), "edge hidden when edge_scope hidden");
    }

    @Test
    void edgeVisibility_allVisible_edgeReturned() {
        var gw = new Gw();
        NodeRef s = NodeRef.of("control_instance", "1");
        NodeRef t = NodeRef.of("risk", "2");
        NodeRef scope = NodeRef.of("unit", "9");
        gw.graph.node(s).node(t).edge(new Edge("has_risk", s, t, scope));
        gw.checker.grant("alice", VIEWER, s).grant("alice", VIEWER, t).grant("alice", VIEWER, scope);
        ReadGateway gateway = gw.build();

        GraphView view = gateway.getEdges(alice, s, EdgeQuery.all());
        assertEquals(1, view.edges().size());
    }

    // ---------- 2. Hidden count is not exposed (adding a hidden edge changes nothing) ----------

    @Test
    void hiddenCount_notExposed_addingHiddenEdgeDoesNotChangeVisibleResult() {
        NodeRef s = NodeRef.of("control_instance", "1");
        NodeRef visible = NodeRef.of("risk", "2");
        NodeRef hidden = NodeRef.of("risk", "3");
        NodeRef scope = NodeRef.of("unit", "9");

        // Baseline: only the visible edge exists.
        var base = new Gw();
        base.graph.node(s).node(visible).edge(new Edge("has_risk", s, visible, scope));
        base.checker.grant("alice", VIEWER, s).grant("alice", VIEWER, visible).grant("alice", VIEWER, scope);
        GraphView baseView = base.build().getEdges(alice, s, EdgeQuery.all());

        // With an additional HIDDEN edge (alice lacks view on `hidden`).
        var withHidden = new Gw();
        withHidden.graph.node(s).node(visible).node(hidden)
                .edge(new Edge("has_risk", s, visible, scope))
                .edge(new Edge("has_risk", s, hidden, scope));
        withHidden.checker.grant("alice", VIEWER, s).grant("alice", VIEWER, visible).grant("alice", VIEWER, scope);
        GraphView hiddenView = withHidden.build().getEdges(alice, s, EdgeQuery.all());

        assertEquals(baseView.edges().size(), hiddenView.edges().size(),
                "a hidden edge must not change the visible edge count (no count-leak)");
        assertEquals(1, hiddenView.edges().size());
        assertFalse(hiddenView.nodes().stream().anyMatch(n -> n.ref().equals(hidden)));
        // Compile-time guarantee: GraphView exposes only nodes()+edges(), no total/hidden field.
    }

    // ---------- 3. RAG: candidate id known, but unauthorized chunk text never resolved ----------

    @Test
    void rag_unauthorizedChunk_textNeverResolved() {
        var gw = new Gw();
        NodeRef priv = NodeRef.of("evidence_object", "77");
        gw.rag.chunk("c1", priv, "TOP SECRET BODY");
        // alice has NO viewer relation on the authoritative object.
        ReadGateway gateway = gw.build();

        List<RagChunk> chunks = gateway.resolveRagChunks(alice, List.of("c1"));
        assertTrue(chunks.isEmpty(), "no chunk for unauthorized caller");
        assertEquals(0, gw.rag.textCalls(), "resolveChunkText must NOT be called on deny");
    }

    @Test
    void rag_authorizedChunk_textResolved() {
        var gw = new Gw();
        NodeRef obj = NodeRef.of("evidence_object", "77");
        gw.rag.chunk("c1", obj, "BODY");
        gw.checker.grant("alice", VIEWER, obj);
        ReadGateway gateway = gw.build();

        List<RagChunk> chunks = gateway.resolveRagChunks(alice, List.of("c1"));
        assertEquals(1, chunks.size());
        assertEquals("BODY", chunks.get(0).text());
        assertEquals(1, gw.rag.textCalls());
    }

    @Test
    void rag_staleVectorProjection_authoritativePolicyEnforced() {
        var gw = new Gw();
        NodeRef obj = NodeRef.of("evidence_object", "88");
        // Authoritative policy is SPECIAL_CATEGORY even though the vector index may have thought "public".
        gw.policy.put(obj, NodePolicy.of(Classification.SPECIAL_CATEGORY));
        gw.rag.chunk("c2", obj, "PII BODY");
        gw.checker.grant("alice", VIEWER, obj); // related, but no special-category clearance
        ReadGateway gateway = gw.build();

        List<RagChunk> chunks = gateway.resolveRagChunks(alice, List.of("c2"));
        assertTrue(chunks.isEmpty(), "authoritative SPECIAL_CATEGORY blocks RAG without clearance");
        assertEquals(0, gw.rag.textCalls());
    }

    @Test
    void rag_forgedChunkId_noAuthoritativeBacking_noText() {
        var gw = new Gw();
        gw.rag.orphanChunk("forged", "LEAK"); // text exists but no authoritative ref
        gw.checker.grant("alice", VIEWER, NodeRef.of("evidence_object", "1")); // unrelated grant
        ReadGateway gateway = gw.build();

        List<RagChunk> chunks = gateway.resolveRagChunks(alice, List.of("forged"));
        assertTrue(chunks.isEmpty(), "forged/orphan chunk id yields no text");
        assertEquals(0, gw.rag.textCalls());
    }

    // ---------- 4. Report/export goes through the gateway; no bypass ----------

    @Test
    void export_scopeHidden_portNeverCalled_emptyBundle() {
        var gw = new Gw();
        NodeRef scope = NodeRef.of("unit", "5");
        gw.audit.items(scope, List.of(new AuditItem(NodeRef.of("task", "1"), "access", "x")));
        // alice lacks EXPORT (editor) on the scope.
        ReadGateway gateway = gw.build();

        AuditBundle bundle = gateway.exportAuditBundle(alice, scope);
        assertTrue(bundle.items().isEmpty());
        assertEquals(0, gw.audit.itemCalls(), "items(scope) must NOT be called for a hidden scope");
    }

    @Test
    void export_scopeAllowed_onlyAuthorizedItemsIncluded() {
        var gw = new Gw();
        NodeRef scope = NodeRef.of("unit", "5");
        NodeRef itemAllowed = NodeRef.of("task", "1");
        NodeRef itemHidden = NodeRef.of("task", "2");
        gw.audit.items(scope, List.of(
                new AuditItem(itemAllowed, "access", "ok"),
                new AuditItem(itemHidden, "access", "secret")));
        gw.checker.grant("alice", EDITOR, scope).grant("alice", EDITOR, itemAllowed); // itemHidden not granted
        ReadGateway gateway = gw.build();

        AuditBundle bundle = gateway.exportAuditBundle(alice, scope);
        assertEquals(1, gw.audit.itemCalls());
        assertEquals(1, bundle.items().size());
        assertEquals(itemAllowed, bundle.items().get(0).ref());
    }

    // ---------- 5. No cross-user cache leak ----------

    @Test
    void crossUser_noCacheLeak_aliceAllowBobDeny() {
        var gw = new Gw();
        NodeRef n = NodeRef.of("control_instance", "1");
        gw.graph.node(n);
        gw.checker.grant("alice", VIEWER, n); // bob NOT granted
        ReadGateway gateway = gw.build();

        assertTrue(gateway.getNode(alice, n).isPresent(), "alice sees the node");
        int afterAlice = gw.checker.callCount();
        assertTrue(gateway.getNode(bob, n).isEmpty(), "bob must NOT receive alice's cached allow");
        assertTrue(gw.checker.callCount() > afterAlice, "bob re-evaluated, not served alice's decision");
    }

    // ---------- 6. Deny-overrides via the gateway (legal hold) + caller-action immutability ----------

    @Test
    void denyOverrides_legalHold_viewMetadataOk_downloadDenied_contentPortNotCalled() {
        var gw = new Gw();
        NodeRef e = NodeRef.of("evidence_object", "42");
        gw.policy.put(e, new NodePolicy(Classification.PUBLIC, true, Set.of())); // legal hold
        gw.checker.grant("alice", VIEWER, e).grant("alice", EDITOR, e);
        gw.evidence.metadata(e, new EvidenceMetadataView(e, "imm-1", "hash-1", "7y", true))
                .content(e, new EvidenceContent(e, "worm://42"));
        ReadGateway gateway = gw.build();

        assertTrue(gateway.resolveEvidence(alice, e).isPresent(), "metadata view allowed under legal hold");
        assertTrue(gateway.downloadEvidenceContent(alice, e).isEmpty(), "download blocked by legal hold");
        assertEquals(0, gw.evidence.contentCalls(), "content port NOT called on download deny");
    }

    @Test
    void deniedEvidence_noPortCalls() {
        var gw = new Gw();
        NodeRef e = NodeRef.of("evidence_object", "42");
        gw.evidence.metadata(e, new EvidenceMetadataView(e, "imm-1", "hash-1", "7y", false))
                .content(e, new EvidenceContent(e, "worm://42"));
        // alice has no relation at all.
        ReadGateway gateway = gw.build();

        assertTrue(gateway.resolveEvidence(alice, e).isEmpty());
        assertTrue(gateway.downloadEvidenceContent(alice, e).isEmpty());
        assertEquals(0, gw.evidence.metadataCalls(), "metadata port not called on view deny");
        assertEquals(0, gw.evidence.contentCalls(), "content port not called on download deny");
    }

    // ---------- Side-channel-free search + bounded traversal ----------

    @Test
    void search_returnsOnlyVisible_hiddenNotCounted() {
        var gw = new Gw();
        NodeRef v1 = NodeRef.of("task", "1");
        NodeRef h1 = NodeRef.of("task", "2");
        NodeRef v2 = NodeRef.of("task", "3");
        gw.graph.node(v1).node(h1).node(v2).candidate(v1).candidate(h1).candidate(v2);
        gw.checker.grant("alice", VIEWER, v1).grant("alice", VIEWER, v2); // h1 hidden
        ReadGateway gateway = gw.build();

        SearchResult result = gateway.search(alice, new SearchQuery("q", Set.of(), 10));
        assertEquals(2, result.results().size(), "only visible matches");
        assertTrue(result.results().stream().noneMatch(n -> n.ref().equals(h1)));
        // SearchResult exposes only results() — no total/hasMore field for a hidden match to leak through.
    }

    @Test
    void search_limitCountsVisibleOnly_hiddenDoNotConsumeLimit() {
        var gw = new Gw();
        NodeRef v1 = NodeRef.of("task", "1");
        NodeRef h1 = NodeRef.of("task", "2");
        NodeRef v2 = NodeRef.of("task", "3");
        NodeRef h2 = NodeRef.of("task", "4");
        NodeRef v3 = NodeRef.of("task", "5");
        gw.graph.node(v1).node(h1).node(v2).node(h2).node(v3)
                .candidate(v1).candidate(h1).candidate(v2).candidate(h2).candidate(v3);
        gw.checker.grant("alice", VIEWER, v1).grant("alice", VIEWER, v2).grant("alice", VIEWER, v3);
        ReadGateway gateway = gw.build();

        SearchResult result = gateway.search(alice, new SearchQuery("q", Set.of(), 2));
        assertEquals(2, result.results().size(), "limit applies to VISIBLE results only");
        assertEquals(v1, result.results().get(0).ref());
        assertEquals(v2, result.results().get(1).ref());
    }

    @Test
    void traverse_hiddenNodeNotExpanded_budgetNotConsumed() {
        var gw = new Gw();
        NodeRef a = NodeRef.of("process", "1");
        NodeRef b = NodeRef.of("step", "2");
        NodeRef c = NodeRef.of("step", "3");
        NodeRef hidden = NodeRef.of("step", "9");
        NodeRef secret = NodeRef.of("step", "10");
        NodeRef scope = NodeRef.of("unit", "1");
        gw.graph.node(a).node(b).node(c).node(hidden).node(secret)
                .edge(new Edge("contains", a, b, scope))
                .edge(new Edge("contains", b, c, scope))
                .edge(new Edge("contains", a, hidden, scope))
                .edge(new Edge("contains", hidden, secret, scope));
        // alice sees a, b, c, scope — but NOT hidden (so `secret` behind it is unreachable).
        gw.checker.grant("alice", VIEWER, a).grant("alice", VIEWER, b)
                .grant("alice", VIEWER, c).grant("alice", VIEWER, scope);
        ReadGateway gateway = gw.build();

        GraphView view = gateway.traverse(alice, new TraverseRequest(a, 3, 10, EdgeQuery.all()));
        assertTrue(view.nodes().stream().anyMatch(n -> n.ref().equals(c)),
                "c reachable: hidden sibling did not consume budget/path");
        assertFalse(view.nodes().stream().anyMatch(n -> n.ref().equals(hidden)), "hidden node not expanded");
        assertFalse(view.nodes().stream().anyMatch(n -> n.ref().equals(secret)), "node behind hidden unreachable");
    }

    @Test
    void traverse_budgetBoundsVisibleNodes() {
        var gw = new Gw();
        NodeRef a = NodeRef.of("process", "1");
        NodeRef b = NodeRef.of("step", "2");
        NodeRef c = NodeRef.of("step", "3");
        NodeRef scope = NodeRef.of("unit", "1");
        gw.graph.node(a).node(b).node(c)
                .edge(new Edge("contains", a, b, scope))
                .edge(new Edge("contains", a, c, scope));
        gw.checker.grant("alice", VIEWER, a).grant("alice", VIEWER, b)
                .grant("alice", VIEWER, c).grant("alice", VIEWER, scope);
        ReadGateway gateway = gw.build();

        GraphView view = gateway.traverse(alice, new TraverseRequest(a, 3, 2, EdgeQuery.all()));
        assertTrue(view.nodes().size() <= 2, "visible-node budget enforced");
    }

    @Test
    void getNode_denied_returnsEmpty() {
        var gw = new Gw();
        NodeRef n = NodeRef.of("control_instance", "1");
        gw.graph.node(n);
        // no grant
        ReadGateway gateway = gw.build();
        assertTrue(gateway.getNode(alice, n).isEmpty());
    }
}
