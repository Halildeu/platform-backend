package com.example.schema.service;

import com.example.schema.model.ColumnInfo;
import com.example.schema.model.Relationship;
import com.example.schema.model.SchemaSnapshot;
import com.example.schema.model.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Schema Health Score — evaluates schema quality against best practices.
 */
@Service
public class SchemaHealthService {

    private static final Logger log = LoggerFactory.getLogger(SchemaHealthService.class);

    public record HealthIssue(String rule, String severity, String table, String detail, int penalty) {}

    public record HealthReport(
        int score,
        String grade,
        int totalIssues,
        Map<String, Integer> issueBySeverity,
        List<HealthIssue> issues,
        Map<String, Object> stats
    ) {}

    private static final Pattern NAMING_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    public HealthReport evaluate(SchemaSnapshot snapshot) {
        List<HealthIssue> issues = new ArrayList<>();

        // Rule 1: Tables without PK
        for (var entry : snapshot.tables().entrySet()) {
            boolean hasPk = entry.getValue().columns().stream().anyMatch(ColumnInfo::pk);
            if (!hasPk) {
                issues.add(new HealthIssue("missing_pk", "high", entry.getKey(),
                    "Table has no primary key", 5));
            }
        }

        // Rule 2: Orphan tables (no relationships)
        Set<String> connected = new HashSet<>();
        for (Relationship rel : snapshot.relationships()) {
            connected.add(rel.fromTable());
            connected.add(rel.toTable());
        }
        for (String table : snapshot.tables().keySet()) {
            if (!connected.contains(table)) {
                issues.add(new HealthIssue("orphan_table", "low", table,
                    "No relationships discovered", 1));
            }
        }

        // Rule 3: Over-wide tables (200+ columns)
        for (var entry : snapshot.tables().entrySet()) {
            int colCount = entry.getValue().columns().size();
            if (colCount > 200) {
                issues.add(new HealthIssue("over_wide", "medium", entry.getKey(),
                    colCount + " columns (recommended max: 50)", 3));
            } else if (colCount > 100) {
                issues.add(new HealthIssue("wide_table", "low", entry.getKey(),
                    colCount + " columns", 1));
            }
        }

        // Rule 4: Naming convention violations
        for (var entry : snapshot.tables().entrySet()) {
            if (!NAMING_PATTERN.matcher(entry.getKey()).matches()) {
                issues.add(new HealthIssue("naming_violation", "low", entry.getKey(),
                    "Table name doesn't follow UPPER_SNAKE_CASE", 2));
            }
            for (ColumnInfo col : entry.getValue().columns()) {
                if (col.name().contains(" ")) {
                    issues.add(new HealthIssue("column_has_spaces", "medium", entry.getKey(),
                        "Column '" + col.name() + "' contains spaces", 3));
                }
            }
        }

        // Rule 5: Duplicate column names suggesting denormalization
        Map<String, Long> colFrequency = new HashMap<>();
        for (var entry : snapshot.tables().entrySet()) {
            for (ColumnInfo col : entry.getValue().columns()) {
                if (!col.name().endsWith("_ID") && !col.name().equals("ID")) {
                    colFrequency.merge(col.name(), 1L, Long::sum);
                }
            }
        }
        long duplicateColumns = colFrequency.values().stream().filter(c -> c > 20).count();

        // Rule 6: Tables with temp/yedek/backup in name
        for (String table : snapshot.tables().keySet()) {
            String upper = table.toUpperCase();
            if (upper.contains("YEDEK") || upper.contains("BACKUP") || upper.contains("TEMP")
                || upper.contains("_OLD") || upper.contains("_BAK")) {
                issues.add(new HealthIssue("temp_table", "medium", table,
                    "Appears to be a temporary/backup table", 2));
            }
        }

        // Calculate score
        int totalPenalty = issues.stream().mapToInt(HealthIssue::penalty).sum();
        int maxScore = snapshot.tables().size() * 2; // 2 points per table max
        int score = Math.max(0, Math.min(100, 100 - (int)(100.0 * totalPenalty / Math.max(maxScore, 1))));

        String grade;
        if (score >= 90) grade = "A";
        else if (score >= 80) grade = "B";
        else if (score >= 70) grade = "C";
        else if (score >= 60) grade = "D";
        else grade = "F";

        Map<String, Integer> bySeverity = issues.stream()
            .collect(Collectors.groupingBy(HealthIssue::severity, Collectors.summingInt(i -> 1)));

        Map<String, Object> stats = Map.of(
            "totalTables", snapshot.tables().size(),
            "totalColumns", snapshot.tables().values().stream().mapToInt(t -> t.columns().size()).sum(),
            "totalRelationships", snapshot.relationships().size(),
            "orphanTables", snapshot.tables().size() - connected.size(),
            "tablesWithPk", snapshot.tables().values().stream().filter(t -> t.columns().stream().anyMatch(ColumnInfo::pk)).count(),
            "avgColumnsPerTable", snapshot.tables().values().stream().mapToInt(t -> t.columns().size()).average().orElse(0),
            "duplicateColumnPatterns", duplicateColumns
        );

        log.info("Schema health: score={}, grade={}, issues={}", score, grade, issues.size());
        return new HealthReport(score, grade, issues.size(), bySeverity, issues, stats);
    }
}
