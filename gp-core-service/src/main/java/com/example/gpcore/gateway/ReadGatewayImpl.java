package com.example.gpcore.gateway;

import com.example.gpcore.authz.AuthorizationDecisionService;
import com.example.gpcore.domain.Action;
import com.example.gpcore.domain.Edge;
import com.example.gpcore.domain.NodeRef;
import com.example.gpcore.domain.Principal;
import com.example.gpcore.gateway.model.AuditBundle;
import com.example.gpcore.gateway.model.AuditItem;
import com.example.gpcore.gateway.model.EdgeQuery;
import com.example.gpcore.gateway.model.EdgeView;
import com.example.gpcore.gateway.model.EvidenceContent;
import com.example.gpcore.gateway.model.EvidenceMetadataView;
import com.example.gpcore.gateway.model.GraphView;
import com.example.gpcore.gateway.model.NodeView;
import com.example.gpcore.gateway.model.RagChunk;
import com.example.gpcore.gateway.model.SearchQuery;
import com.example.gpcore.gateway.model.SearchResult;
import com.example.gpcore.gateway.model.TraverseRequest;
import com.example.gpcore.port.content.AuditBundlePort;
import com.example.gpcore.port.content.EvidenceReadPort;
import com.example.gpcore.port.content.GraphReadPort;
import com.example.gpcore.port.content.RagChunkPort;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read Gateway implementation — the ONLY class permitted to inject the raw
 * content ports (enforced structurally by the ArchUnit choke-point test). It
 * never trusts a port result for visibility: every node/edge/chunk/bundle item
 * is gated by {@link AuthorizationDecisionService} before it is surfaced, and
 * content ports are not invoked at all on a deny.
 */
public class ReadGatewayImpl implements ReadGateway {

    private final AuthorizationDecisionService decisions;
    private final GraphReadPort graphPort;
    private final EvidenceReadPort evidencePort;
    private final RagChunkPort ragPort;
    private final AuditBundlePort auditPort;

    /** Internal cap on candidates examined per call — bounds work; never reflected in any response. */
    private final int maxInternalScan;

    public ReadGatewayImpl(AuthorizationDecisionService decisions,
                           GraphReadPort graphPort,
                           EvidenceReadPort evidencePort,
                           RagChunkPort ragPort,
                           AuditBundlePort auditPort,
                           int maxInternalScan) {
        this.decisions = decisions;
        this.graphPort = graphPort;
        this.evidencePort = evidencePort;
        this.ragPort = ragPort;
        this.auditPort = auditPort;
        this.maxInternalScan = Math.max(1, maxInternalScan);
    }

    @Override
    public Optional<NodeView> getNode(Principal principal, NodeRef ref) {
        // Decision BEFORE content read (no content before decision).
        if (decisions.decideView(principal, ref).denied()) {
            return Optional.empty();
        }
        return graphPort.findNode(ref);
    }

    @Override
    public GraphView getEdges(Principal principal, NodeRef entry, EdgeQuery query) {
        if (decisions.decideView(principal, entry).denied()) {
            return GraphView.empty(); // entry hidden → reveal nothing, not even that it exists
        }
        EdgeQuery q = query == null ? EdgeQuery.all() : query;
        List<Edge> candidates = safeFindEdges(entry, q);

        // Bounded bulk authorization (ADR-0035 §6): warm the decision cache for all
        // distinct endpoints/scopes via a single batched relationship check so the
        // per-edge decisions below are cache hits rather than N sequential calls.
        warmEndpoints(principal, candidates, q);

        Map<NodeRef, NodeView> nodes = new LinkedHashMap<>();
        addVisibleNode(principal, entry, nodes);
        LinkedHashSet<EdgeView> edges = new LinkedHashSet<>();
        int scanned = 0;
        for (Edge edge : candidates) {
            if (scanned++ >= maxInternalScan) {
                break;
            }
            if (!q.matchesEdgeType(edge.edgeType())) {
                continue;
            }
            if (decisions.decideEdge(principal, edge).denied()) {
                continue; // hidden edge: pruned during assembly, neighbour not surfaced/counted
            }
            edges.add(new EdgeView(edge.edgeType(), edge.source(), edge.target(), edge.scope()));
            addVisibleNode(principal, edge.source(), nodes);
            addVisibleNode(principal, edge.target(), nodes);
        }
        return new GraphView(new ArrayList<>(nodes.values()), new ArrayList<>(edges));
    }

    @Override
    public GraphView traverse(Principal principal, TraverseRequest request) {
        if (decisions.decideView(principal, request.entry()).denied()) {
            return GraphView.empty();
        }
        EdgeQuery q = request.query();
        Map<NodeRef, NodeView> nodes = new LinkedHashMap<>();
        LinkedHashSet<EdgeView> edges = new LinkedHashSet<>();
        Deque<Frontier> queue = new ArrayDeque<>();

        addVisibleNode(principal, request.entry(), nodes);
        queue.add(new Frontier(request.entry(), 0));
        int scanned = 0;

        while (!queue.isEmpty() && nodes.size() < request.budget() && scanned < maxInternalScan) {
            Frontier cur = queue.poll();
            if (cur.depth() >= request.depth()) {
                continue; // depth bound — do not expand further
            }
            for (Edge edge : safeFindEdges(cur.ref(), q)) {
                if (scanned++ >= maxInternalScan) {
                    break;
                }
                if (!q.matchesEdgeType(edge.edgeType())) {
                    continue;
                }
                if (decisions.decideEdge(principal, edge).denied()) {
                    continue; // hidden: not expanded, not counted, does not consume visible budget
                }
                NodeRef neighbour = edge.source().equals(cur.ref()) ? edge.target() : edge.source();
                if (!q.matchesNodeType(neighbour.type())) {
                    continue;
                }
                edges.add(new EdgeView(edge.edgeType(), edge.source(), edge.target(), edge.scope()));
                if (!nodes.containsKey(neighbour) && nodes.size() < request.budget()) {
                    addVisibleNode(principal, neighbour, nodes);
                    queue.add(new Frontier(neighbour, cur.depth() + 1));
                }
            }
        }
        return new GraphView(new ArrayList<>(nodes.values()), new ArrayList<>(edges));
    }

    @Override
    public SearchResult search(Principal principal, SearchQuery query) {
        List<NodeRef> candidates = safeSearchCandidates(query);
        List<NodeView> visible = new ArrayList<>();
        int scanned = 0;
        for (NodeRef ref : candidates) {
            if (scanned++ >= maxInternalScan || visible.size() >= query.limit()) {
                break;
            }
            if (!query.matchesNodeType(ref.type())) {
                continue;
            }
            if (decisions.decideView(principal, ref).denied()) {
                continue; // hidden match: excluded and NOT counted anywhere
            }
            graphPort.findNode(ref).ifPresent(visible::add);
        }
        return new SearchResult(visible);
    }

    @Override
    public Optional<EvidenceMetadataView> resolveEvidence(Principal principal, NodeRef ref) {
        if (decisions.decideAction(principal, ref, Action.VIEW).denied()) {
            return Optional.empty();
        }
        return evidencePort.findMetadata(ref);
    }

    @Override
    public Optional<EvidenceContent> downloadEvidenceContent(Principal principal, NodeRef ref) {
        // DOWNLOAD requires a stronger relation than VIEW + survives legal-hold/classification ABAC.
        if (decisions.decideAction(principal, ref, Action.DOWNLOAD).denied()) {
            return Optional.empty(); // content port intentionally NOT called on deny
        }
        return evidencePort.resolveContent(ref);
    }

    @Override
    public List<RagChunk> resolveRagChunks(Principal principal, List<String> candidateChunkIds) {
        List<RagChunk> resolved = new ArrayList<>();
        if (candidateChunkIds == null) {
            return resolved;
        }
        for (String chunkId : candidateChunkIds) {
            if (chunkId == null) {
                continue;
            }
            // Candidate id is NOT a capability: rehydrate the AUTHORITATIVE object ref,
            // then decide RAG_READ against the authoritative policy — never the vector metadata.
            NodeRef authoritative = ragPort.authoritativeRef(chunkId).orElse(null);
            if (authoritative == null) {
                continue; // forged/stale id with no trustworthy backing → no text
            }
            if (decisions.decideAction(principal, authoritative, Action.RAG_READ).denied()) {
                continue; // unauthorized: resolveChunkText intentionally NOT called
            }
            ragPort.resolveChunkText(chunkId)
                    .ifPresent(text -> resolved.add(new RagChunk(chunkId, authoritative, text)));
        }
        return resolved;
    }

    @Override
    public AuditBundle exportAuditBundle(Principal principal, NodeRef scope) {
        // Scope authorized FIRST — a hidden scope never reaches the port (no enumeration/membership leak).
        if (decisions.decideAction(principal, scope, Action.EXPORT).denied()) {
            return AuditBundle.empty(scope);
        }
        List<AuditItem> all;
        try {
            all = auditPort.items(scope);
        } catch (RuntimeException e) {
            return AuditBundle.empty(scope);
        }
        List<AuditItem> allowed = new ArrayList<>();
        if (all != null) {
            for (AuditItem item : all) {
                if (item != null && !decisions.decideAction(principal, item.ref(), Action.EXPORT).denied()) {
                    allowed.add(item);
                }
            }
        }
        return new AuditBundle(scope, allowed);
    }

    // --- helpers ---

    private void warmEndpoints(Principal principal, List<Edge> candidates, EdgeQuery q) {
        LinkedHashSet<NodeRef> distinct = new LinkedHashSet<>();
        int scanned = 0;
        for (Edge edge : candidates) {
            if (scanned++ >= maxInternalScan) {
                break;
            }
            if (!q.matchesEdgeType(edge.edgeType())) {
                continue;
            }
            distinct.add(edge.source());
            distinct.add(edge.target());
            if (edge.scope() != null) {
                distinct.add(edge.scope());
            }
        }
        if (!distinct.isEmpty()) {
            decisions.decideViewBatch(principal, new ArrayList<>(distinct));
        }
    }

    private void addVisibleNode(Principal principal, NodeRef ref, Map<NodeRef, NodeView> sink) {
        if (sink.containsKey(ref)) {
            return;
        }
        // Endpoints reached here have already passed an edge/view decision; load content now.
        graphPort.findNode(ref).ifPresent(view -> sink.put(ref, view));
    }

    private List<Edge> safeFindEdges(NodeRef entry, EdgeQuery query) {
        try {
            List<Edge> e = graphPort.findEdges(entry, query);
            return e == null ? List.of() : e;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private List<NodeRef> safeSearchCandidates(SearchQuery query) {
        try {
            List<NodeRef> c = graphPort.searchCandidates(query);
            return c == null ? List.of() : c;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private record Frontier(NodeRef ref, int depth) {}
}
