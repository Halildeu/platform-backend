package com.example.schema.dto;

/**
 * Codex 019dda1c iter-29: typed payload for the master-data internal
 * read endpoint (`GET /api/v1/schema/master-data/{kind}`). Mirrors the
 * shape that mfe-access ScopeAssignModal and mfe-users UserDetailDrawer
 * already consume from permission-service's public master-data endpoint
 * (which now proxies through to this schema-service endpoint instead of
 * the empty workcube_mikrolink Postgres mirror).
 *
 * <p>Backed by direct SQL against the workcube_mikrolink MSSQL schema —
 * source of truth for the four scope-picker entity types
 * (companies/projects/branches/departments). The schema-service already
 * holds an MSSQL DataSource for metadata extraction (sys.tables/sys.columns
 * via SchemaExtractService); this endpoint reuses that connection for the
 * data rows themselves rather than introducing a second MSSQL client in
 * permission-service.
 */
public record MasterDataItemDto(Long id, String name, boolean status) {}
