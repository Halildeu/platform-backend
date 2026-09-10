package com.example.schema.service;

import com.example.schema.model.Relationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * BFS-based path finder between two tables in the relationship graph.
 * Answers: "How do I join TABLE_A to TABLE_B?"
 */
@Service
public class PathFinderService {

    private static final Logger log = LoggerFactory.getLogger(PathFinderService.class);
    private static final int MAX_DEPTH = 6;

    public record PathStep(String table, String column, String joinTo, String joinColumn, double confidence) {}
    public record PathResult(String from, String to, int hops, List<PathStep> path, String joinSql) {}

    /**
     * Find shortest path between two tables using BFS on the relationship graph.
     */
    public PathResult findPath(String fromTable, String toTable, List<Relationship> relationships) {
        if (fromTable.equals(toTable)) {
            return new PathResult(fromTable, toTable, 0, List.of(), "-- Same table, no join needed");
        }

        // Build adjacency list (bidirectional)
        Map<String, List<Edge>> adj = adjacency(relationships);

        // BFS
        Queue<List<String>> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();
        Map<String, Edge> parentEdge = new HashMap<>();
        Map<String, String> parentTable = new HashMap<>();

        queue.add(List.of(fromTable));
        visited.add(fromTable);

        while (!queue.isEmpty()) {
            List<String> currentPath = queue.poll();
            String current = currentPath.getLast();

            if (current.equals(toTable)) {
                // Reconstruct path
                return buildResult(fromTable, toTable, currentPath, parentEdge, parentTable);
            }

            if (currentPath.size() > MAX_DEPTH) continue;

            List<Edge> neighbors = adj.getOrDefault(current, List.of());
            // Sort by confidence (prefer high-confidence edges)
            neighbors.sort(Comparator.comparingDouble(Edge::confidence).reversed());

            for (Edge edge : neighbors) {
                if (!visited.contains(edge.target)) {
                    visited.add(edge.target);
                    parentEdge.put(edge.target, edge);
                    parentTable.put(edge.target, current);
                    List<String> newPath = new ArrayList<>(currentPath);
                    newPath.add(edge.target);
                    queue.add(newPath);
                }
            }
        }

        log.info("No path found between {} and {}", fromTable, toTable);
        return new PathResult(fromTable, toTable, -1, List.of(), "-- No join path found");
    }

    /**
     * Find all shortest paths (up to limit) between two tables.
     */
    public List<PathResult> findAllPaths(String fromTable, String toTable,
                                          List<Relationship> relationships, int limit) {
        log.info("Finding paths from {} to {} (limit={}), {} relationships available",
            fromTable, toTable, limit, relationships.size());

        // Build adjacency (bidirectional)
        Map<String, List<Edge>> adj = adjacency(relationships);

        log.info("Adjacency built: {} nodes, from={} has {} edges, to={} has {} edges",
            adj.size(),
            fromTable, adj.getOrDefault(fromTable, List.of()).size(),
            toTable, adj.getOrDefault(toTable, List.of()).size());

        // BFS with path tracking
        List<PathResult> results = new ArrayList<>();
        Deque<List<String>> queue = new ArrayDeque<>();
        List<String> start = new ArrayList<>();
        start.add(fromTable);
        queue.add(start);
        int shortestLength = Integer.MAX_VALUE;

        while (!queue.isEmpty() && results.size() < limit) {
            List<String> path = queue.poll();
            String current = path.get(path.size() - 1);

            // Only once a first path is known: MAX_VALUE + 1 overflows to MIN_VALUE, and the
            // old unguarded comparison skipped every path, so this method always returned [].
            if (shortestLength != Integer.MAX_VALUE && path.size() > shortestLength + 1) continue;
            if (path.size() > MAX_DEPTH) continue;

            if (current.equals(toTable) && path.size() > 1) {
                shortestLength = Math.min(shortestLength, path.size());
                results.add(buildSimpleResult(fromTable, toTable, path, adj));
                continue;
            }

            for (Edge edge : adj.getOrDefault(current, List.of())) {
                if (!path.contains(edge.target())) {
                    List<String> newPath = new ArrayList<>(path);
                    newPath.add(edge.target());
                    queue.add(newPath);
                }
            }
        }

        log.info("Found {} paths from {} to {}", results.size(), fromTable, toTable);
        return results;
    }

    private PathResult buildResult(String from, String to, List<String> tablePath,
                                    Map<String, Edge> parentEdge, Map<String, String> parentTable) {
        List<PathStep> steps = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT *\nFROM ").append(from).append(" t0\n");

        for (int i = 1; i < tablePath.size(); i++) {
            String table = tablePath.get(i);
            Edge edge = parentEdge.get(table);
            String prev = parentTable.get(table);

            steps.add(new PathStep(prev, edge.fromCol, table, edge.toCol, edge.confidence));
            sql.append(joinClause(table, i, edge));
        }

        return new PathResult(from, to, tablePath.size() - 1, steps, sql.toString().trim());
    }

    private static Map<String, List<Edge>> adjacency(List<Relationship> relationships) {
        Map<String, List<Edge>> adj = new HashMap<>();
        for (Relationship rel : relationships) {
            adj.computeIfAbsent(rel.fromTable(), k -> new ArrayList<>())
                .add(new Edge(rel.toTable(), rel.fromColumn(), rel.toColumn(),
                    rel.fromColumns(), rel.toColumns(), rel.confidence(), "forward"));
            adj.computeIfAbsent(rel.toTable(), k -> new ArrayList<>())
                .add(new Edge(rel.fromTable(), rel.toColumn(), rel.fromColumn(),
                    rel.toColumns(), rel.fromColumns(), rel.confidence(), "reverse"));
        }
        return adj;
    }

    /**
     * One JOIN line joining on every column pair of the edge. A composite key joined on
     * its representative pair alone matches rows of every parent that shares that value
     * (gitops#3631, Codex 01a08afc P1); the {@code PathStep} keeps the representative pair
     * for display, the SQL is what a report runs.
     */
    private static String joinClause(String table, int i, Edge edge) {
        StringBuilder on = new StringBuilder();
        for (int c = 0; c < edge.fromCols.size(); c++) {
            if (c > 0) on.append(" AND ");
            on.append(String.format("t%d.%s = t%d.%s", i - 1, edge.fromCols.get(c), i, edge.toCols.get(c)));
        }
        return String.format("JOIN %s t%d ON %s\n", table, i, on);
    }

    private PathResult buildSimpleResult(String from, String to, List<String> tablePath,
                                          Map<String, List<Edge>> adj) {
        List<PathStep> steps = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT *\nFROM ").append(from).append(" t0\n");

        for (int i = 1; i < tablePath.size(); i++) {
            String prev = tablePath.get(i - 1);
            String curr = tablePath.get(i);
            Edge edge = adj.getOrDefault(prev, List.of()).stream()
                .filter(e -> e.target.equals(curr))
                .max(Comparator.comparingDouble(Edge::confidence))
                .orElse(null);

            if (edge != null) {
                steps.add(new PathStep(prev, edge.fromCol, curr, edge.toCol, edge.confidence));
                sql.append(joinClause(curr, i, edge));
            }
        }

        return new PathResult(from, to, tablePath.size() - 1, steps, sql.toString().trim());
    }

    private record Edge(String target, String fromCol, String toCol,
                        List<String> fromCols, List<String> toCols,
                        double confidence, String direction) {}
}
