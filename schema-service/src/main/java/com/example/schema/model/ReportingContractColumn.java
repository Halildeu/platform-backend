package com.example.schema.model;

/**
 * One column inside a {@link ReportingContractTable} (Adım 12).
 *
 * <p>{@code type} is the canonical SQL type string. It is mapped from
 * {@link ColumnInfo#dataType()} — Codex {@code 019e2d64} S4 explicitly
 * rejected renaming {@code ColumnInfo.dataType} itself, because that
 * record still backs the legacy {@code /snapshot} endpoint consumed by
 * the frontend and report-service. The rename happens only in this new
 * projection.
 *
 * <p>The etl-worker consumer
 * ({@code etl_worker/contracts.py:ColumnSpec}) reads exactly
 * {@code name} / {@code type} / {@code nullable}; other
 * {@link ColumnInfo} fields ({@code maxLength}, {@code identity},
 * {@code pk}, {@code ordinal}) are intentionally NOT projected to keep
 * the contract minimal.
 */
public record ReportingContractColumn(
    String name,
    String type,
    boolean nullable
) {
}
