package com.example.schema.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SchemaSnapshot(
    String version,
    Metadata metadata,
    Map<String, TableInfo> tables,
    List<Relationship> relationships,
    Map<String, List<String>> domains,
    Analysis analysis
) {
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
