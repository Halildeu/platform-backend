package com.example.schema.service;

import com.example.schema.model.ColumnInfo;
import com.example.schema.model.SchemaSnapshot;
import com.example.schema.model.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Schema drift detection — compare current schema with previous snapshots.
 */
@Service
public class SchemaDriftService {

    private static final Logger log = LoggerFactory.getLogger(SchemaDriftService.class);

    // In-memory snapshot history (replace with DB for production)
    private final Map<String, List<SnapshotSummary>> history = new ConcurrentHashMap<>();

    public record SnapshotSummary(Instant timestamp, int tableCount, int columnCount, int relationshipCount) {}

    public record DriftReport(
        Instant comparedAt,
        SnapshotSummary previous,
        SnapshotSummary current,
        List<String> addedTables,
        List<String> removedTables,
        List<TableChange> modifiedTables,
        Summary summary
    ) {}

    public record TableChange(String table, List<String> addedColumns, List<String> removedColumns,
                               List<ColumnTypeChange> typeChanges) {}
    public record ColumnTypeChange(String column, String oldType, String newType) {}
    public record Summary(int tablesAdded, int tablesRemoved, int tablesModified,
                           int columnsAdded, int columnsRemoved) {}

    // Previous snapshot cache
    private final Map<String, SchemaSnapshot> previousSnapshots = new ConcurrentHashMap<>();

    /**
     * Record current snapshot and compute drift from previous.
     */
    public DriftReport computeDrift(SchemaSnapshot current, String schema) {
        SchemaSnapshot previous = previousSnapshots.get(schema);
        previousSnapshots.put(schema, current);

        // Record history
        SnapshotSummary currentSummary = new SnapshotSummary(
            Instant.now(), current.tables().size(),
            current.tables().values().stream().mapToInt(t -> t.columns().size()).sum(),
            current.relationships().size()
        );
        history.computeIfAbsent(schema, k -> new ArrayList<>()).add(currentSummary);

        if (previous == null) {
            return new DriftReport(
                Instant.now(), null, currentSummary,
                List.of(), List.of(), List.of(),
                new Summary(0, 0, 0, 0, 0)
            );
        }

        // Compare
        Set<String> oldTables = previous.tables().keySet();
        Set<String> newTables = current.tables().keySet();

        List<String> added = newTables.stream().filter(t -> !oldTables.contains(t)).sorted().toList();
        List<String> removed = oldTables.stream().filter(t -> !newTables.contains(t)).sorted().toList();

        List<TableChange> modified = new ArrayList<>();
        int totalColsAdded = 0, totalColsRemoved = 0;

        for (String table : oldTables) {
            if (!newTables.contains(table)) continue;
            TableInfo oldTable = previous.tables().get(table);
            TableInfo newTable = current.tables().get(table);

            Set<String> oldCols = new HashSet<>();
            Map<String, String> oldTypes = new HashMap<>();
            for (ColumnInfo c : oldTable.columns()) { oldCols.add(c.name()); oldTypes.put(c.name(), c.dataType()); }

            Set<String> newCols = new HashSet<>();
            Map<String, String> newTypes = new HashMap<>();
            for (ColumnInfo c : newTable.columns()) { newCols.add(c.name()); newTypes.put(c.name(), c.dataType()); }

            List<String> addedCols = newCols.stream().filter(c -> !oldCols.contains(c)).sorted().toList();
            List<String> removedCols = oldCols.stream().filter(c -> !newCols.contains(c)).sorted().toList();

            List<ColumnTypeChange> typeChanges = new ArrayList<>();
            for (String col : oldCols) {
                if (newCols.contains(col) && !oldTypes.get(col).equals(newTypes.get(col))) {
                    typeChanges.add(new ColumnTypeChange(col, oldTypes.get(col), newTypes.get(col)));
                }
            }

            if (!addedCols.isEmpty() || !removedCols.isEmpty() || !typeChanges.isEmpty()) {
                modified.add(new TableChange(table, addedCols, removedCols, typeChanges));
                totalColsAdded += addedCols.size();
                totalColsRemoved += removedCols.size();
            }
        }

        SnapshotSummary prevSummary = new SnapshotSummary(
            previous.metadata().extractedAt(),
            previous.tables().size(),
            previous.tables().values().stream().mapToInt(t -> t.columns().size()).sum(),
            previous.relationships().size()
        );

        log.info("Drift: +{} -{} tables, {} modified", added.size(), removed.size(), modified.size());
        return new DriftReport(
            Instant.now(), prevSummary, currentSummary,
            added, removed, modified,
            new Summary(added.size(), removed.size(), modified.size(), totalColsAdded, totalColsRemoved)
        );
    }

    public List<SnapshotSummary> getHistory(String schema) {
        return history.getOrDefault(schema, List.of());
    }
}
