package com.example.schema.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Authoritative {@code CHECK} constraint extracted from MSSQL
 * {@code sys.check_constraints} — {@code authoritative_mssql} truth tier
 * (ADR-0020 §2.3, capability M3 — Codex 019e2d7d).
 *
 * <p>Phase B1-3. {@code columnName} is {@code null} for a table-level
 * check ({@code parent_column_id = 0}) and non-null for a column-level
 * check — binding a table-level constraint to a fake column would mislead
 * UI / analysis. {@code definition} is the raw {@code CHECK} SQL and is
 * NOT parsed.
 */
public record CheckConstraintInfo(
    String name,
    String schema,
    String table,
    String columnName,
    String definition,
    boolean isDisabled,
    boolean isNotTrusted
) {

    /**
     * True for a table-level check (not bound to a single column).
     * Derived — {@code @JsonIgnore} keeps it out of the wire contract.
     */
    @JsonIgnore
    public boolean isTableLevel() {
        return columnName == null;
    }
}
