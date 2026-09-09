package com.example.schema.catalog;

import com.example.schema.model.ChangeDataInfo;
import com.example.schema.model.CheckConstraintInfo;
import com.example.schema.model.DatabaseOptionsInfo;
import com.example.schema.model.DefaultConstraintInfo;
import com.example.schema.model.ForeignKeyInfo;
import com.example.schema.model.IndexInfo;
import com.example.schema.model.ObjectInfo;
import com.example.schema.model.StorageInfo;
import com.example.schema.model.TableInfo;
import com.example.schema.model.UniqueConstraintInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The metadata-catalog surface the snapshot builder reads, independent of which
 * database engine backs it.
 *
 * <p>Until #3594 the service spoke only MSSQL: every read went through
 * {@code SchemaExtractService} and its {@code sys.*} queries, and the single
 * {@code spring.datasource} was pinned to {@code jdbc:sqlserver://}. Connecting
 * the IFS ERP Oracle database needs a second engine whose catalog has different
 * names ({@code ALL_TABLES} / {@code ALL_TAB_COLUMNS} / {@code ALL_CONSTRAINTS})
 * and different semantics, so the snapshot builder now depends on this interface
 * and a {@link CatalogSourceRegistry} resolves the concrete reader per source.
 *
 * <p><strong>Only {@link #extractTables} is mandatory.</strong> The snapshot
 * builder wraps every other read in its own try/catch and degrades to an empty
 * inventory, which is what lets an engine ship without a full port of all
 * fourteen surfaces. An implementation that cannot serve a surface must say so
 * explicitly (return empty and log) rather than inherit a silent default — a
 * caller cannot distinguish "this engine has no such concept" from "the read
 * failed" unless the implementation is explicit about it.
 */
public interface CatalogReader {

    /** Identifies which configured source this reader serves (e.g. {@code workcube}, {@code ifs}). */
    String sourceId();

    /** Human-facing engine name for diagnostics and the source picker. */
    String engine();

    /**
     * The schema (MSSQL) or owner (Oracle) this source reads when a caller
     * names none. Resolved by the caller BEFORE the snapshot is built, so the
     * cache key, the log line and any error all carry the real name: an
     * Oracle snapshot cached and reported as schema 'null' is what happens
     * otherwise, and the MSSQL default is the wrong fallback for Oracle.
     */
    String defaultSchema();

    /**
     * Tables with their columns and primary keys. The one mandatory surface:
     * a failure here genuinely blocks the snapshot and propagates as 503.
     */
    Map<String, TableInfo> extractTables(String schema);

    /** Schemas (MSSQL) or owners (Oracle) visible to this connection, with table counts. */
    List<Map<String, Object>> listSchemas();

    /** Declared foreign keys — the authoritative half of the relationship graph. */
    List<ForeignKeyInfo> extractForeignKeys(String schema);

    /** Unique constraints and unique indexes. */
    List<UniqueConstraintInfo> extractUniqueConstraints(String schema);

    /** View source text, used by the relationship discovery parser. */
    Map<String, String> getViewDefinitions(String schema);

    /** Physical index inventory. */
    List<IndexInfo> extractIndexes(String schema);

    /** Check constraints. */
    List<CheckConstraintInfo> extractCheckConstraints(String schema);

    /** Column default expressions. */
    List<DefaultConstraintInfo> extractDefaultConstraints(String schema);

    /** Catalog objects (tables, views, procedures) with create/modify stamps. */
    List<ObjectInfo> extractObjects(String schema);

    /** Per-table storage footprint. */
    List<StorageInfo> extractStorage(String schema);

    /** Change-tracking / CDC / temporal features. Engine-specific; may be empty. */
    List<ChangeDataInfo> extractChangeData(String schema);

    /** Database-level options. May be {@code null} when the engine has no equivalent. */
    DatabaseOptionsInfo extractDatabaseOptions();

    /** Approximate row counts per table. */
    Map<String, Long> getRowCounts(String schema);

    /** Table names only — the cheap path for lookup and existence checks. */
    Set<String> getTableNames(String schema);
}
