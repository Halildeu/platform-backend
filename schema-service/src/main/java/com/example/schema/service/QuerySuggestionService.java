package com.example.schema.service;

import com.example.schema.model.ColumnInfo;
import com.example.schema.model.Relationship;
import com.example.schema.model.SchemaSnapshot;
import com.example.schema.model.TableInfo;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.Locale;

/**
 * Generates contextual SQL query suggestions based on schema patterns.
 */
@Service
public class QuerySuggestionService {

    public record QuerySuggestion(String title, String description, String sql, String pattern) {}

    public List<QuerySuggestion> suggest(String tableName, SchemaSnapshot snapshot) {
        TableInfo table = snapshot.tables().get(tableName);
        if (table == null) return List.of();

        List<QuerySuggestion> suggestions = new ArrayList<>();

        // 1. Basic SELECT
        suggestions.add(new QuerySuggestion(
            "Preview Data",
            "Top 100 rows from " + tableName,
            String.format("SELECT TOP 100 *\nFROM [%s].[%s]\nORDER BY 1 DESC;",
                table.schema(), tableName),
            "basic_select"
        ));

        // 2. Row count
        suggestions.add(new QuerySuggestion(
            "Row Count",
            "Total rows in " + tableName,
            String.format("SELECT COUNT(*) AS row_count\nFROM [%s].[%s];",
                table.schema(), tableName),
            "count"
        ));

        // 3. FK joins — for each outgoing relationship
        List<Relationship> outgoing = snapshot.relationships().stream()
            .filter(r -> r.fromTable().equals(tableName))
            .toList();

        for (Relationship rel : outgoing.stream().limit(5).toList()) {
            suggestions.add(new QuerySuggestion(
                "Join to " + rel.toTable(),
                String.format("Join via %s.%s → %s", tableName, String.join(", ", rel.fromColumns()), rel.toTable()),
                String.format("SELECT t.*, r.*\nFROM [%s].[%s] t\nJOIN [%s].[%s] r ON %s\nORDER BY t.[%s] DESC\nOFFSET 0 ROWS FETCH NEXT 100 ROWS ONLY;",
                    table.schema(), tableName, table.schema(), rel.toTable(),
                    joinCondition(rel, "t", "r"), rel.fromColumn()),
                "join"
            ));
        }

        // 4. Aggregation by FK (if there are FK columns)
        List<String> fkCols = outgoing.stream().map(Relationship::fromColumn).distinct().toList();
        if (!fkCols.isEmpty()) {
            String groupCol = fkCols.getFirst();
            suggestions.add(new QuerySuggestion(
                "Group by " + groupCol,
                "Count distribution by " + groupCol,
                String.format("SELECT [%s], COUNT(*) AS cnt\nFROM [%s].[%s]\nGROUP BY [%s]\nORDER BY cnt DESC;",
                    groupCol, table.schema(), tableName, groupCol),
                "aggregation"
            ));
        }

        // 5. Date distribution (if date columns exist)
        Optional<ColumnInfo> dateCol = table.columns().stream()
            .filter(c -> c.dataType().toLowerCase(Locale.ROOT).contains("date") || c.dataType().toLowerCase(Locale.ROOT).contains("datetime"))
            .findFirst();

        dateCol.ifPresent(col -> suggestions.add(new QuerySuggestion(
            "Timeline by " + col.name(),
            "Monthly distribution over " + col.name(),
            String.format("SELECT YEAR([%s]) AS yr, MONTH([%s]) AS mo, COUNT(*) AS cnt\nFROM [%s].[%s]\nWHERE [%s] IS NOT NULL\nGROUP BY YEAR([%s]), MONTH([%s])\nORDER BY yr DESC, mo DESC;",
                col.name(), col.name(), table.schema(), tableName,
                col.name(), col.name(), col.name()),
            "time_series"
        )));

        // 6. NULL analysis
        List<String> nullableCols = table.columns().stream()
            .filter(ColumnInfo::nullable)
            .map(ColumnInfo::name)
            .limit(5)
            .toList();

        if (!nullableCols.isEmpty()) {
            String nullChecks = nullableCols.stream()
                .map(c -> String.format("SUM(CASE WHEN [%s] IS NULL THEN 1 ELSE 0 END) AS [%s_nulls]", c, c))
                .collect(Collectors.joining(",\n  "));

            suggestions.add(new QuerySuggestion(
                "NULL Analysis",
                "Check NULL distribution in key columns",
                String.format("SELECT COUNT(*) AS total_rows,\n  %s\nFROM [%s].[%s];",
                    nullChecks, table.schema(), tableName),
                "data_quality"
            ));
        }

        // 7. Incoming refs — "Who references this table?"
        List<Relationship> incoming = snapshot.relationships().stream()
            .filter(r -> r.toTable().equals(tableName))
            .toList();

        if (!incoming.isEmpty()) {
            Relationship topRef = incoming.getFirst();
            suggestions.add(new QuerySuggestion(
                "Referenced by " + topRef.fromTable(),
                tableName + " referenced from " + incoming.size() + " tables",
                String.format("-- %s is referenced by %d tables\n-- Example: %s.%s\nSELECT r.*, t.*\nFROM [%s].[%s] r\nJOIN [%s].[%s] t ON %s\nORDER BY 1 DESC\nOFFSET 0 ROWS FETCH NEXT 100 ROWS ONLY;",
                    tableName, incoming.size(), topRef.fromTable(), String.join(", ", topRef.fromColumns()),
                    table.schema(), topRef.fromTable(), table.schema(), tableName,
                    joinCondition(topRef, "r", "t")),
                "reverse_join"
            ));
        }

        return suggestions;
    }

    /**
     * The ON clause of a relationship, every column pair AND-ed, with {@code sourceAlias}
     * on the referencing side and {@code targetAlias} on the referenced side. Both the
     * forward and the reverse suggestion use it: a composite key joined on its
     * representative pair alone returns rows of other parents (gitops#3631, Codex
     * 01a08afc P1 — the reverse join was still doing that in iteration 1).
     */
    static String joinCondition(Relationship rel, String sourceAlias, String targetAlias) {
        StringBuilder on = new StringBuilder();
        for (int c = 0; c < rel.fromColumns().size(); c++) {
            if (c > 0) on.append(" AND ");
            on.append(String.format("%s.[%s] = %s.[%s]",
                sourceAlias, rel.fromColumns().get(c), targetAlias, rel.toColumns().get(c)));
        }
        return on.toString();
    }
}
