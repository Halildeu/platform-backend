package com.example.schema.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Annotation persistence — in-memory with export/import capability.
 * TODO: Replace with PostgreSQL table for production.
 */
@Service
public class AnnotationService {

    private static final Logger log = LoggerFactory.getLogger(AnnotationService.class);

    public record Annotation(
        String key,        // "TABLE:EMPLOYEES" or "EMPLOYEES.EMPLOYEE_ID"
        String table,
        String column,     // null for table-level
        String text,
        List<String> tags,
        String author,
        Instant updatedAt
    ) {}

    private final Map<String, Annotation> store = new ConcurrentHashMap<>();

    public Annotation save(String key, String table, String column, String text, List<String> tags, String author) {
        var ann = new Annotation(key, table, column, text, tags != null ? tags : List.of(),
            author != null ? author : "anonymous", Instant.now());
        store.put(key, ann);
        log.info("Annotation saved: {} (by {})", key, author);
        return ann;
    }

    public Annotation get(String key) {
        return store.get(key);
    }

    public List<Annotation> getForTable(String table) {
        return store.values().stream()
            .filter(a -> a.table().equals(table))
            .sorted(Comparator.comparing(Annotation::key))
            .toList();
    }

    public Map<String, Annotation> getAll() {
        return Map.copyOf(store);
    }

    public void delete(String key) {
        store.remove(key);
    }

    /**
     * Bulk import — merges with existing, newer timestamps win.
     */
    public int importAnnotations(Map<String, Annotation> annotations) {
        int imported = 0;
        for (var entry : annotations.entrySet()) {
            Annotation existing = store.get(entry.getKey());
            if (existing == null || entry.getValue().updatedAt().isAfter(existing.updatedAt())) {
                store.put(entry.getKey(), entry.getValue());
                imported++;
            }
        }
        log.info("Imported {} annotations (total: {})", imported, store.size());
        return imported;
    }

    public Map<String, Object> stats() {
        long tableLevelCount = store.values().stream().filter(a -> a.column() == null).count();
        long columnLevelCount = store.size() - tableLevelCount;
        Set<String> tagSet = new HashSet<>();
        store.values().forEach(a -> tagSet.addAll(a.tags()));
        return Map.of(
            "total", store.size(),
            "tableLevel", tableLevelCount,
            "columnLevel", columnLevelCount,
            "uniqueTags", tagSet.size(),
            "tags", tagSet.stream().sorted().toList()
        );
    }
}
