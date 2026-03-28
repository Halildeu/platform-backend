package com.example.report.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SortTranslator {

    public String translate(List<Map<String, String>> sortModel, Set<String> allowedColumns,
                            String defaultSort, String defaultSortDirection) {
        if (sortModel != null && !sortModel.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map<String, String> sort : sortModel) {
                String colId = sort.get("colId");
                String sortDir = sort.get("sort");
                if (colId != null && allowedColumns.contains(colId)) {
                    String direction = "desc".equalsIgnoreCase(sortDir) ? "DESC" : "ASC";
                    parts.add("[" + colId + "] " + direction);
                }
            }
            if (!parts.isEmpty()) {
                return String.join(", ", parts);
            }
        }

        if (defaultSort != null && !defaultSort.isBlank() && allowedColumns.contains(defaultSort)) {
            String dir = defaultSortDirection != null ? defaultSortDirection : "ASC";
            return "[" + defaultSort + "] " + dir;
        }

        return null;
    }
}
