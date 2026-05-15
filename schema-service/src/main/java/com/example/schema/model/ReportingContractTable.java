package com.example.schema.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * One table inside a {@link ReportingContractSnapshot} (Adım 12).
 *
 * <p>{@code schema} + {@code name} together uniquely identify the table.
 * {@code columns} preserves the column order of the underlying
 * {@link TableInfo} (ordinal extraction) so a downstream consumer can
 * rely on deterministic column ordering.
 *
 * <p>All three field names are currently single words so snake_case is a
 * no-op, but the {@code @JsonNaming} annotation is applied defensively
 * (Codex {@code 019e2d64} S1): a nested record does NOT inherit the
 * parent's naming strategy in Jackson, so a future multi-word field
 * ({@code row_count}, {@code schema_mode}, …) would otherwise silently
 * serialise as camelCase and break the snake_case wire contract.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ReportingContractTable(
    String schema,
    String name,
    List<ReportingContractColumn> columns
) {
}
