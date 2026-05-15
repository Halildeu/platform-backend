package com.example.schema.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * Adım 12 reporting refactor — target contract emitted by
 * {@code GET /api/v1/schema/reporting-contract}.
 *
 * <p>This is a deliberately narrow, consumer-stable projection of the
 * full {@link SchemaSnapshot}, scoped to the {@code ReportingAllowlist}
 * table set. The etl-worker Python consumer
 * ({@code platform-backend/etl-worker/etl_worker/contracts.py}) parses
 * exactly these fields and fails closed on a missing
 * {@code contract_version} / {@code allowlist_name} /
 * {@code allowlist_version} or a non-list {@code tables}.
 *
 * <p>Codex {@code 019e2d64} plan-time AGREE (Opt-B′): a NEW endpoint,
 * NOT a change to the legacy {@code /api/v1/schema/snapshot}. The
 * legacy endpoint stays {@code SchemaSnapshot} (camelCase, {@code tables}
 * map, column {@code dataType}) so the frontend schema-explorer and the
 * report-service {@code SchemaTruthService} Tier-1 client are untouched.
 *
 * <p>JSON shape (snake_case via {@link JsonNaming}):
 * <pre>
 * {
 *   "contract_version": "1",
 *   "allowlist_name": "ReportingAllowlist",
 *   "allowlist_version": "V1",
 *   "tables": [ { "schema": "...", "name": "...", "columns": [ ... ] } ]
 * }
 * </pre>
 *
 * <p><b>contract_version vs allowlist_version</b> (Codex {@code 019e2d64}
 * S3): {@code contract_version} tracks the wire shape / parser semantics
 * ({@code tables} list, column {@code type}, required fields). It bumps
 * only when the JSON structure changes. {@code allowlist_version} tracks
 * which table set filtered the snapshot — V1 → V2 can change the table
 * set without bumping {@code contract_version}. Keeping them separate
 * avoids spurious {@code EX_PROTOCOL=76} fail-closed on the consumer
 * when only the allowlist evolves.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ReportingContractSnapshot(
    String contractVersion,
    String allowlistName,
    String allowlistVersion,
    List<ReportingContractTable> tables
) {
}
