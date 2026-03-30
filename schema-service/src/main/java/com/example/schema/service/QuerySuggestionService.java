package com.example.schema.service;

import com.example.schema.model.ColumnInfo;
import com.example.schema.model.Relationship;
import com.example.schema.model.SchemaSnapshot;
import com.example.schema.model.TableInfo;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

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
                String.format("Join via %s.%s → %s", tableName, rel.fromColumn(), rel.toTable()),
                String.format("SELECT t.*, r.*\nFROM [%s].[%s] t\nJOIN [%s].[%s] r ON t.[%s] = r.[%s]\nORDER BY t.[%s] DESC\nOFFSET 0 ROWS FETCH NEXT 100 ROWS ONLY;",
                    table.schema(), tableName, table.schema(), rel.toTable(),
                    rel.fromColumn(), rel.toColumn(), rel.fromColumn()),
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
            .filter(c -> c.dataType().toLowerCase().contains("date") || c.dataType().toLowerCase().contains("datetime"))
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
                String.format("-- %s is referenced by %d tables\n-- Example: %s.%s\nSELECT r.*, t.*\nFROM [%s].[%s] r\nJOIN [%s].[%s] t ON r.[%s] = t.[%s]\nORDER BY 1 DESC\nOFFSET 0 ROWS FETCH NEXT 100 ROWS ONLY;",
                    tableName, incoming.size(), topRef.fromTable(), topRef.fromColumn(),
                    table.schema(), topRef.fromTable(), table.schema(), tableName,
                    topRef.fromColumn(), topRef.toColumn()),
                "reverse_join"
            ));
        }

        return suggestions;
    }
}
