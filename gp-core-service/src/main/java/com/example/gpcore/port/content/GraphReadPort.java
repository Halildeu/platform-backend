package com.example.gpcore.port.content;

import com.example.gpcore.domain.Edge;
import com.example.gpcore.domain.NodeRef;
import com.example.gpcore.gateway.model.EdgeQuery;
import com.example.gpcore.gateway.model.NodeView;
import com.example.gpcore.gateway.model.SearchQuery;

import java.util.List;
import java.util.Optional;

/**
 * CONTENT port for the typed graph (ontology v2 §7 — PG adjacency in a later
 * wave). It returns node content and structural adjacency.
 *
 * <p>This is a content port: by the ArchUnit choke-point rule it may be injected
 * ONLY by the Read Gateway implementation (and Spring config) — never by the
 * authz layer or application code, which must go through {@code ReadGateway}
 * (Codex 019f1913 #4).
 *
 * <p>Structural methods ({@link #findEdges}, {@link #searchCandidates}) return
 * refs/edges (structure needed to make a visibility decision) but NOT node
 * content; node content ({@link #findNode}) is loaded only after an allow.
 *
 * <p><b>Candidate-bounding contract:</b> implementations MUST return a BOUNDED
 * candidate set (paging / top-K / over-fetch) — the gateway scans the full list
 * returned. Bounding work is the port's responsibility precisely so that hidden
 * candidate density never has to be capped at the gateway (which would leak as a
 * side-channel into the visible result — Codex 019f1913 post-impl #2).
 */
public interface GraphReadPort {

    /** Node content; loaded only after a VIEW allow (Codex 019f1913 #4 — no content before decision). */
    Optional<NodeView> findNode(NodeRef ref);

    /** Candidate edges incident to {@code entry} matching the filter (structure only). */
    List<Edge> findEdges(NodeRef entry, EdgeQuery query);

    /** Candidate node refs matching a search (structure only; visibility decided by the gateway). */
    List<NodeRef> searchCandidates(SearchQuery query);
}
