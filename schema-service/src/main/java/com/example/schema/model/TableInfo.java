package com.example.schema.model;

import java.util.List;

/**
 * A table or view with its columns.
 *
 * <p>{@code comment} (gitops#3631) is the source's own description of the object —
 * {@code ALL_TAB_COMMENTS} on Oracle, which IFS fills for most of its views. Nullable
 * and additive; the older constructors keep working and leave it {@code null}.
 */
public record TableInfo(
    String name,
    String schema,
    List<ColumnInfo> columns,
    Long rowCount,
    int columnCount,
    String comment
) {
    public TableInfo(String name, String schema, List<ColumnInfo> columns) {
        this(name, schema, columns, null, columns.size(), null);
    }

    /** Pre-#3631 five-field shape: no comment. */
    public TableInfo(String name, String schema, List<ColumnInfo> columns, Long rowCount, int columnCount) {
        this(name, schema, columns, rowCount, columnCount, null);
    }
}
