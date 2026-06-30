package com.example.gpcore.testsupport;

import com.example.gpcore.domain.Edge;
import com.example.gpcore.domain.NodeRef;
import com.example.gpcore.gateway.model.EdgeQuery;
import com.example.gpcore.gateway.model.NodeView;
import com.example.gpcore.gateway.model.SearchQuery;
import com.example.gpcore.port.content.GraphReadPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory {@link GraphReadPort}: registered node content, an edge list, and search candidates. */
public class InMemoryGraphReadPort implements GraphReadPort {

    private final Map<NodeRef, NodeView> nodes = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private final List<NodeRef> searchCandidates = new ArrayList<>();

    public InMemoryGraphReadPort node(NodeRef ref, Map<String, Object> attributes) {
        nodes.put(ref, new NodeView(ref, attributes));
        return this;
    }

    public InMemoryGraphReadPort node(NodeRef ref) {
        return node(ref, Map.of("type", ref.type(), "id", ref.id()));
    }

    public InMemoryGraphReadPort edge(Edge edge) {
        edges.add(edge);
        return this;
    }

    public InMemoryGraphReadPort candidate(NodeRef ref) {
        searchCandidates.add(ref);
        return this;
    }

    @Override
    public Optional<NodeView> findNode(NodeRef ref) {
        return Optional.ofNullable(nodes.get(ref));
    }

    @Override
    public List<Edge> findEdges(NodeRef entry, EdgeQuery query) {
        List<Edge> out = new ArrayList<>();
        for (Edge e : edges) {
            if (e.source().equals(entry) || e.target().equals(entry)) {
                out.add(e);
            }
        }
        return out;
    }

    @Override
    public List<NodeRef> searchCandidates(SearchQuery query) {
        return new ArrayList<>(searchCandidates);
    }
}
