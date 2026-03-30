package com.example.schema.model;

import java.util.List;

public record TableInfo(
    String name,
    String schema,
    List<ColumnInfo> columns,
    Long rowCount,
    int columnCount
) {
    public TableInfo(String name, String schema, List<ColumnInfo> columns) {
        this(name, schema, columns, null, columns.size());
    }
}
