package com.example.schema.service;

import com.example.schema.model.Relationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Domain detection via graph-based community detection.
 * Uses a simple label propagation algorithm (no external dependencies).
 */
@Service
public class DomainClusteringService {

    private static final Logger log = LoggerFactory.getLogger(DomainClusteringService.class);

    private static final int MAX_ITERATIONS = 50;
    private static final int MIN_DOMAIN_SIZE = 3;

    public Map<String, List<String>> detectDomains(Set<String> tableNames,
                                                    List<Relationship> relationships) {
        // Build adjacency list
        Map<String, Set<String>> adj = new HashMap<>();
        for (String t : tableNames) adj.put(t, new HashSet<>());

        for (Relationship rel : relationships) {
            if (tableNames.contains(rel.fromTable()) && tableNames.contains(rel.toTable())) {
                adj.computeIfAbsent(rel.fromTable(), k -> new HashSet<>()).add(rel.toTable());
                adj.computeIfAbsent(rel.toTable(), k -> new HashSet<>()).add(rel.fromTable());
            }
        }

        // Label propagation
        Map<String, String> labels = new HashMap<>();
        for (String t : tableNames) labels.put(t, t);

        Random rng = new Random(42);
        List<String> nodes = new ArrayList<>(tableNames);

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            Collections.shuffle(nodes, rng);
            boolean changed = false;

            for (String node : nodes) {
                Set<String> neighbors = adj.get(node);
                if (neighbors == null || neighbors.isEmpty()) continue;

                // Find most frequent label among neighbors
                Map<String, Long> labelCounts = neighbors.stream()
                    .map(labels::get)
                    .collect(Collectors.groupingBy(l -> l, Collectors.counting()));

                String bestLabel = labelCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(labels.get(node));

                if (!bestLabel.equals(labels.get(node))) {
                    labels.put(node, bestLabel);
                    changed = true;
                }
            }

            if (!changed) {
                log.info("Label propagation converged at iteration {}", iter);
                break;
            }
        }

        // Group by label
        Map<String, List<String>> clusters = labels.entrySet().stream()
            .collect(Collectors.groupingBy(
                Map.Entry::getValue,
                Collectors.mapping(Map.Entry::getKey, Collectors.toList())
            ));

        // Name and filter domains
        Map<String, List<String>> domains = new LinkedHashMap<>();
        List<String> others = new ArrayList<>();

        clusters.entrySet().stream()
            .sorted((a, b) -> b.getValue().size() - a.getValue().size())
            .forEach(entry -> {
                List<String> tables = entry.getValue().stream().sorted().toList();
                if (tables.size() < MIN_DOMAIN_SIZE) {
                    others.addAll(tables);
                    return;
                }
                String label = inferLabel(tables);
                // Deduplicate label names
                String finalLabel = label;
                int suffix = 2;
                while (domains.containsKey(finalLabel)) {
                    finalLabel = label + "_" + suffix++;
                }
                domains.put(finalLabel, tables);
            });

        if (!others.isEmpty()) {
            domains.put("OTHER", others.stream().sorted().toList());
        }

        // Isolated tables
        Set<String> assigned = domains.values().stream()
            .flatMap(List::stream).collect(Collectors.toSet());
        List<String> isolated = tableNames.stream()
            .filter(t -> !assigned.contains(t))
            .sorted().toList();
        if (!isolated.isEmpty()) {
            domains.put("ISOLATED", isolated);
        }

        log.info("Detected {} domains from {} tables", domains.size(), tableNames.size());
        return domains;
    }

    private String inferLabel(List<String> tables) {
        Map<String, Long> prefixCounts = tables.stream()
            .map(t -> t.contains("_") ? t.substring(0, t.indexOf('_')) : t)
            .collect(Collectors.groupingBy(p -> p, Collectors.counting()));

        return prefixCounts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("UNKNOWN");
    }
}
