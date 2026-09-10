package com.example.schema.service;

import com.example.schema.model.SchemaSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;

/**
 * Column-level lineage — traces data flow through views and relationships.
 */
@Service
public class ColumnLineageService {

    private static final Logger log = LoggerFactory.getLogger(ColumnLineageService.class);

    private static final Pattern SELECT_COL = Pattern.compile(
        "(\\w+)\\.(\\w+)\\s+(?:AS\\s+)?(\\w+)", Pattern.CASE_INSENSITIVE
    );

    public record LineageNode(String table, String column, String type) {} // type: "source", "transform", "target"
    public record LineageEdge(LineageNode from, LineageNode to, String transformation) {}
    public record LineageGraph(String targetTable, String targetColumn, List<LineageNode> nodes, List<LineageEdge> edges) {}

    /**
     * Trace lineage for a specific column by analyzing view definitions and relationships.
     */
    public LineageGraph traceColumn(String tableName, String columnName,
                                     SchemaSnapshot snapshot,
                                     Map<String, String> viewDefinitions) {
        List<LineageNode> nodes = new ArrayList<>();
        List<LineageEdge> edges = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        LineageNode target = new LineageNode(tableName, columnName, "target");
        nodes.add(target);

        // 1. Check if this column comes from a FK relationship
        for (var rel : snapshot.relationships()) {
            // Any pair of a composite key counts; the source node is the pair's own column.
            int i = rel.fromTable().equals(tableName) ? rel.fromColumns().indexOf(columnName) : -1;
            if (i >= 0) {
                LineageNode source = new LineageNode(rel.toTable(), rel.toColumns().get(i), "source");
                nodes.add(source);
                edges.add(new LineageEdge(source, target, "FK reference"));
            }
        }

        // 2. Check if any views produce this column
        if (viewDefinitions != null) {
            for (var entry : viewDefinitions.entrySet()) {
                String viewName = entry.getKey();
                String sql = entry.getValue();
                if (sql == null) continue;

                // Check if view references this table.column
                String upper = sql.toUpperCase(Locale.ROOT);
                if (upper.contains(tableName.toUpperCase(Locale.ROOT)) && upper.contains(columnName.toUpperCase(Locale.ROOT))) {
                    // Parse the view to find column mappings
                    Matcher m = SELECT_COL.matcher(sql);
                    while (m.find()) {
                        String srcTable = m.group(1).toUpperCase(Locale.ROOT);
                        String srcCol = m.group(2).toUpperCase(Locale.ROOT);
                        String alias = m.group(3).toUpperCase(Locale.ROOT);

                        if (alias.equals(columnName.toUpperCase(Locale.ROOT)) || srcCol.equals(columnName.toUpperCase(Locale.ROOT))) {
                            String key = viewName + "." + srcTable + "." + srcCol;
                            if (visited.add(key)) {
                                LineageNode viewNode = new LineageNode(viewName, alias, "transform");
                                LineageNode sourceNode = new LineageNode(srcTable, srcCol, "source");
                                if (!nodes.contains(viewNode)) nodes.add(viewNode);
                                if (!nodes.contains(sourceNode)) nodes.add(sourceNode);
                                edges.add(new LineageEdge(sourceNode, viewNode, "VIEW: " + viewName));
                            }
                        }
                    }
                }
            }
        }

        // 3. Find downstream consumers (tables that reference this table.column via FK)
        for (var rel : snapshot.relationships()) {
            int i = rel.toTable().equals(tableName) ? rel.toColumns().indexOf(columnName) : -1;
            if (i >= 0) {
                LineageNode consumer = new LineageNode(rel.fromTable(), rel.fromColumns().get(i), "consumer");
                if (!nodes.contains(consumer)) nodes.add(consumer);
                edges.add(new LineageEdge(target, consumer, "Referenced by"));
            }
        }

        log.info("Lineage for {}.{}: {} nodes, {} edges", tableName, columnName, nodes.size(), edges.size());
        return new LineageGraph(tableName, columnName, nodes, edges);
    }
}
