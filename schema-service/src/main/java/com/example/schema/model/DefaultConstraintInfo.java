package com.example.schema.model;

/**
 * Authoritative {@code DEFAULT} constraint extracted from MSSQL
 * {@code sys.default_constraints} — {@code authoritative_mssql} truth
 * tier (ADR-0020 §2.3, capability M3 — Codex 019e2d7d).
 *
 * <p>Phase B1-3. This carries the constraint <strong>name</strong>,
 * which {@link ColumnInfo#defaultExpression()} does not — the name is
 * required for migration {@code DROP CONSTRAINT}. The redundancy with
 * {@code ColumnInfo.defaultExpression} is intentional:
 * {@code ColumnInfo.defaultExpression} is column-ergonomic,
 * {@code DefaultConstraintInfo} is the authoritative constraint
 * inventory. {@code definition} is the raw default SQL.
 */
public record DefaultConstraintInfo(
    String name,
    String schema,
    String table,
    String columnName,
    String definition
) {}
