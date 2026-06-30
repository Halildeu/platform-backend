package com.example.gpcore.gateway;

import com.example.gpcore.domain.NodeRef;
import com.example.gpcore.domain.Principal;
import com.example.gpcore.gateway.model.AuditBundle;
import com.example.gpcore.gateway.model.EvidenceContent;
import com.example.gpcore.gateway.model.EvidenceMetadataView;
import com.example.gpcore.gateway.model.GraphView;
import com.example.gpcore.gateway.model.NodeView;
import com.example.gpcore.gateway.model.RagChunk;
import com.example.gpcore.gateway.model.SearchQuery;
import com.example.gpcore.gateway.model.SearchResult;
import com.example.gpcore.gateway.model.TraverseRequest;

import java.util.List;
import java.util.Optional;

/**
 * The single Read Gateway choke-point (ADR-0035 §1). EVERY read path —
 * UI / gp-ai / RAG / search / report / export — goes through this interface;
 * nothing accesses PG / vector / blob / Ollama-context directly (read-only is
 * forbidden too). Each method fixes its own {@link com.example.gpcore.domain.Action}
 * internally — the caller never passes an action — so a caller cannot downgrade
 * a DOWNLOAD/EXPORT to a VIEW to slip past a deny (Codex 019f1913 #8).
 *
 * <p>All visibility filtering happens via {@code AuthorizationDecisionService}.
 * Denied/hidden objects are absent from results and are never counted, named, or
 * hinted at in any response field (no count-leak / side-channel).
 */
public interface ReadGateway {

    /** A single node, or empty if not viewable (no error, no existence leak). VIEW. */
    Optional<NodeView> getNode(Principal principal, NodeRef ref);

    /** Visible edges incident to {@code entry} (pruned during assembly, not post-filtered). VIEW. */
    GraphView getEdges(Principal principal, NodeRef entry, com.example.gpcore.gateway.model.EdgeQuery query);

    /** Bounded, permission-filtered traversal; hidden nodes are never expanded. VIEW. */
    GraphView traverse(Principal principal, TraverseRequest request);

    /** Search returning ONLY visible matches; hidden matches are not counted. VIEW. */
    SearchResult search(Principal principal, SearchQuery query);

    /** Evidence metadata if viewable, else empty. VIEW (metadata only, no content). */
    Optional<EvidenceMetadataView> resolveEvidence(Principal principal, NodeRef ref);

    /** Evidence content handle if downloadable, else empty (content port not called on deny). DOWNLOAD. */
    Optional<EvidenceContent> downloadEvidenceContent(Principal principal, NodeRef ref);

    /**
     * Resolve RAG chunk text for AI context. Each candidate id is rehydrated to
     * its AUTHORITATIVE object policy and decided as RAG_READ; an unauthorized
     * chunk's text is never resolved, even if its id is known/forged. RAG_READ.
     */
    List<RagChunk> resolveRagChunks(Principal principal, List<String> candidateChunkIds);

    /** Export an audit bundle for {@code scope}; scope is authorized first. EXPORT. */
    AuditBundle exportAuditBundle(Principal principal, NodeRef scope);
}
