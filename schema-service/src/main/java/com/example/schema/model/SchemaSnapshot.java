package com.example.schema.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Full schema snapshot.
 *
 * <p>Phase B1-2 (Codex 019e2d7d, ADR-0020 §2.3): additive top-level
 * {@code foreignKeys} / {@code uniqueConstraints} authoritative inventory.
 * Single-column FKs are also mirrored into {@code relationships}
 * ({@code source="fk_constraint"}); composite FKs live in
 * {@code foreignKeys} only.
 *
 * <p>Phase B1-3 (capability M3): additive top-level
 * {@code checkConstraints} / {@code defaultConstraints} authoritative
 * inventory. Legacy 8-arg and 6-arg constructors keep older callers
 * compiling — the new inventory lists default empty.
 */
public record SchemaSnapshot(
    String version,
    Metadata metadata,
    Map<String, TableInfo> tables,
    List<Relationship> relationships,
    List<ForeignKeyInfo> foreignKeys,
    List<UniqueConstraintInfo> uniqueConstraints,
    List<CheckConstraintInfo> checkConstraints,
    List<DefaultConstraintInfo> defaultConstraints,
    Map<String, List<String>> domains,
    Analysis analysis
) {
    /**
     * Legacy 8-arg constructor — B1-2 shape (before the check / default
     * constraint inventory). The new lists default to empty.
     */
    public SchemaSnapshot(String version, Metadata metadata, Map<String, TableInfo> tables,
                          List<Relationship> relationships,
                          List<ForeignKeyInfo> foreignKeys,
                          List<UniqueConstraintInfo> uniqueConstraints,
                          Map<String, List<String>> domains, Analysis analysis) {
        this(version, metadata, tables, relationships, foreignKeys, uniqueConstraints,
             List.of(), List.of(), domains, analysis);
    }

    /**
     * Legacy 6-arg constructor — pre-B1-2 shape (before any authoritative
     * constraint inventory). All four inventory lists default to empty.
     */
    public SchemaSnapshot(String version, Metadata metadata, Map<String, TableInfo> tables,
                          List<Relationship> relationships, Map<String, List<String>> domains,
                          Analysis analysis) {
        this(version, metadata, tables, relationships,
             List.of(), List.of(), List.of(), List.of(), domains, analysis);
    }

    public record Metadata(
        String dbType,
        String host,
        String database,
        String schema,
        Instant extractedAt,
        int tableCount,
        int columnCount,
        int relationshipCount,
        int domainCount
    ) {}

    public record Analysis(
        List<DeadTable> deadTables,
        List<HubTable> hubTables
    ) {}

    public record DeadTable(String table, String reason, Long rowCount) {}
    public record HubTable(String table, int incomingRefs) {}
}
