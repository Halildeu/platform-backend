package com.example.schema.model;

import java.util.List;

/**
 * One table inside a {@link ReportingContractSnapshot} (Adım 12).
 *
 * <p>{@code schema} + {@code name} together uniquely identify the table.
 * {@code columns} preserves the column order of the underlying
 * {@link TableInfo} (ordinal extraction) so a downstream consumer can
 * rely on deterministic column ordering.
 *
 * <p>All three field names are already snake_case-compatible
 * ({@code schema}, {@code name}, {@code columns}) so no
 * {@code @JsonProperty} mapping is needed; the parent record's
 * {@code @JsonNaming} strategy is a no-op on single-word fields.
 */
public record ReportingContractTable(
    String schema,
    String name,
    List<ReportingContractColumn> columns
) {
}
