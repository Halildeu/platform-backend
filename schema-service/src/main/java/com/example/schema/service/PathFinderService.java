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
    /** Upper bound on alternatives one call may enumerate, whatever the caller asks for. */
    public static final int MAX_ALTERNATIVES = 10;

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
     * Every shortest join path between two tables, up to {@code limit} (at most
     * {@link #MAX_ALTERNATIVES}), one per distinct edge sequence: two relationships between
     * the same tables (ORDERS.BILL_TO → CUSTOMER and ORDERS.SHIP_TO → CUSTOMER) are two paths
     * with two different JOINs, not one path returned twice (Codex 01a08afc iter-2 #4). Only
     * paths of the minimum hop count are returned (#5).
     *
     * <p>Two plain BFS passes first measure every table's distance from the source and to the
     * target — O(V+E), and an unreachable target costs nothing more (iter-3 #1: enumerating
     * walks before knowing the target is reachable exhausted a 384m heap on a 34-table
     * clique). The enumeration then walks only edges that lie on a shortest path
     * ({@code fromDist + 1 + toDist == shortest}), depth-first, so every branch ends at the
     * target and the work is bounded by the results asked for, not by the graph. Adjacency
     * is confidence-sorted, so the first result is the path {@link #findPath} picks.
     */
    public List<PathResult> findAllPaths(String fromTable, String toTable,
                                          List<Relationship> relationships, int limit) {
        int cap = Math.min(limit, MAX_ALTERNATIVES);
        log.info("Finding paths from {} to {} (limit={}), {} relationships available",
            fromTable, toTable, cap, relationships.size());
        List<PathResult> results = new ArrayList<>();
        if (fromTable.equals(toTable) || cap <= 0) return results;

        Map<String, List<Edge>> adj = adjacency(relationships);
        for (List<Edge> edges : adj.values()) {
            edges.sort(Comparator.comparingDouble(Edge::confidence).reversed());
        }
        Map<String, Integer> fromDist = distances(adj, fromTable);
        Integer shortest = fromDist.get(toTable);
        if (shortest == null || shortest > MAX_DEPTH) {
            log.info("No path found between {} and {}", fromTable, toTable);
            return results;
        }
        Map<String, Integer> toDist = distances(adj, toTable);

        Set<String> seenSql = new HashSet<>();
        Deque<Walk> stack = new ArrayDeque<>();
        stack.push(new Walk(List.of(fromTable), List.of()));
        while (!stack.isEmpty() && results.size() < cap) {
            Walk walk = stack.pop();
            String current = walk.tables().getLast();
            int hops = walk.edges().size();
            List<Walk> deeper = new ArrayList<>();
            for (Edge edge : adj.getOrDefault(current, List.of())) {
                Integer rest = toDist.get(edge.target());
                if (rest == null || hops + 1 + rest != shortest) continue;
                Walk next = walk.extend(edge);
                if (edge.target().equals(toTable)) {
                    PathResult result = buildFromEdges(fromTable, toTable, next);
                    if (seenSql.add(result.joinSql())) results.add(result);
                    if (results.size() >= cap) break;
                } else {
                    deeper.add(next);
                }
            }
            // Push in reverse so the highest-confidence continuation is popped first.
            for (int i = deeper.size() - 1; i >= 0; i--) stack.push(deeper.get(i));
        }

        log.info("Found {} paths from {} to {}", results.size(), fromTable, toTable);
        return results;
    }

    /** Hop distance from {@code start} to every reachable table, capped at MAX_DEPTH. */
    private static Map<String, Integer> distances(Map<String, List<Edge>> adj, String start) {
        Map<String, Integer> dist = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        dist.put(start, 0);
        queue.add(start);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            int d = dist.get(current);
            if (d >= MAX_DEPTH) continue;
            for (Edge edge : adj.getOrDefault(current, List.of())) {
                if (dist.putIfAbsent(edge.target(), d + 1) == null) queue.add(edge.target());
            }
        }
        return dist;
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

    private PathResult buildFromEdges(String from, String to, Walk walk) {
        List<PathStep> steps = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT *\nFROM ").append(from).append(" t0\n");
        for (int i = 0; i < walk.edges().size(); i++) {
            Edge edge = walk.edges().get(i);
            String prev = walk.tables().get(i);
            String table = walk.tables().get(i + 1);
            steps.add(new PathStep(prev, edge.fromCol, table, edge.toCol, edge.confidence));
            sql.append(joinClause(table, i + 1, edge));
        }
        return new PathResult(from, to, walk.edges().size(), steps, sql.toString().trim());
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

    private record Edge(String target, String fromCol, String toCol,
                        List<String> fromCols, List<String> toCols,
                        double confidence, String direction) {}

    /** A partial path: the tables visited so far and the edge taken into each one after the first. */
    private record Walk(List<String> tables, List<Edge> edges) {
        Walk extend(Edge edge) {
            List<String> t = new ArrayList<>(tables);
            t.add(edge.target());
            List<Edge> e = new ArrayList<>(edges);
            e.add(edge);
            return new Walk(List.copyOf(t), List.copyOf(e));
        }
    }
}
