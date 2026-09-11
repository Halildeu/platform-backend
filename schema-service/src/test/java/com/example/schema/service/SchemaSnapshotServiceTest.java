package com.example.schema.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.schema.exception.SnapshotUnavailableException;
import com.example.schema.catalog.CatalogSourceRegistry;
import com.example.schema.model.ObjectInfo;
import com.example.schema.model.SchemaSnapshot;
import com.example.schema.model.StorageInfo;
import com.example.schema.model.TableInfo;
import com.example.schema.service.discovery.RelationshipDiscoveryService;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase B1-5 (capability M1 — Codex 019e3270): {@code SchemaSnapshotService}
 * integration guard for the B1 authoritative inventories. Each {@code sys.*}
 * extraction is wrapped in a non-fatal try/catch — a failing read must NOT
 * break the snapshot. This pins that contract for {@code extractObjects},
 * {@code extractStorage}, {@code extractChangeData} and
 * {@code extractDatabaseOptions}: a failed extraction → empty / null inventory
 * + snapshot still built; success → the inventory is carried through. Other
 * collaborators are left as Mockito defaults (empty collections).
 */
class SchemaSnapshotServiceTest {

    private final SchemaExtractService extract = mock(SchemaExtractService.class);
    private final RelationshipDiscoveryService discovery = mock(RelationshipDiscoveryService.class);
    private final DomainClusteringService clustering = mock(DomainClusteringService.class);
    // The snapshot builder now reaches its reader through the source registry
    // (gitops#3594). A registry holding only the MSSQL reader reproduces the
    // single-source topology these tests were written against.
    private final CatalogSourceRegistry sources = registryOf(extract);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SchemaSnapshotService service =
            new SchemaSnapshotService(sources, discovery, clustering, meters);

    private static StorageInfo storageRow(String table, long rowCount) {
        return new StorageInfo(table, "workcube_mikrolink", rowCount, 800, 700, 400, 200, 80, 20);
    }

    private Timer phaseTimer(String phase) {
        return meters.find(SchemaSnapshotService.PHASE_TIMER)
                .tag("source", CatalogSourceRegistry.PRIMARY_SOURCE_ID)
                .tag("phase", phase)
                .timer();
    }

    // --- gitops#3652: row counts ride on the storage inventory; the dedicated
    //     sys.partitions pass runs only when storage is absent ---

    @Test
    void rowCountsComeFromStorageWhenStorageIsPresent() {
        var invoice = new TableInfo("INVOICE", "workcube_mikrolink", List.of());
        var empty = new TableInfo("EMPTY_TABLE", "workcube_mikrolink", List.of());
        when(extract.extractTables(anyString())).thenReturn(Map.of(invoice.name(), invoice, empty.name(), empty));
        when(extract.extractStorage(anyString()))
                .thenReturn(List.of(storageRow("INVOICE", 1_234L), storageRow("EMPTY_TABLE", 0L)));

        SchemaSnapshot snap = service.buildSnapshot(null, "workcube_mikrolink");

        assertThat(snap.tables().get("INVOICE").rowCount()).isEqualTo(1_234L);
        assertThat(snap.tables().get("EMPTY_TABLE").rowCount()).isZero();
        verify(extract, never()).getRowCounts(anyString());
    }

    @Test
    void rowCountsFallBackToTheDedicatedReadWhenStorageIsEmpty() {
        // Oracle has no storage inventory, and a failed MSSQL storage read
        // degrades to an empty one: the row counts must still arrive.
        var invoice = new TableInfo("INVOICE", "workcube_mikrolink", List.of());
        when(extract.extractTables(anyString())).thenReturn(Map.of(invoice.name(), invoice));
        when(extract.extractStorage(anyString())).thenThrow(new RuntimeException("storage read timed out"));
        when(extract.getRowCounts(anyString())).thenReturn(Map.of("INVOICE", 77L));

        SchemaSnapshot snap = service.buildSnapshot(null, "workcube_mikrolink");

        assertThat(snap.tables().get("INVOICE").rowCount()).isEqualTo(77L);
        assertThat(snap.storage()).isEmpty();
    }

    @Test
    void rowCountsOf_firstRowWinsOnADuplicateTableName() {
        assertThat(SchemaSnapshotService.rowCountsOf(
                List.of(storageRow("A", 1L), storageRow("A", 2L), storageRow("B", 3L))))
                .containsExactly(Map.entry("A", 1L), Map.entry("B", 3L));
    }

    // --- gitops#3652: every phase of a build is timed, success or failure ---

    @Test
    void everyPhaseIsTimedAndTheBuildTimerRecordsOnce() {
        service.buildSnapshot(null, "workcube_mikrolink");

        for (String phase : List.of("tables", "views", "foreignKeys", "uniqueConstraints",
                "checkConstraints", "defaultConstraints", "indexes", "objects", "storage",
                "changeData", "databaseOptions", "relationships", "domains", "rowCounts")) {
            assertThat(phaseTimer(phase)).as("phase timer " + phase).isNotNull();
            assertThat(phaseTimer(phase).count()).as("phase timer " + phase).isEqualTo(1);
        }
        Timer build = meters.find(SchemaSnapshotService.BUILD_TIMER)
                .tag("source", CatalogSourceRegistry.PRIMARY_SOURCE_ID).timer();
        assertThat(build).isNotNull();
        assertThat(build.count()).isEqualTo(1);
    }

    @Test
    void aFailingPhaseIsStillTimed() {
        when(extract.extractStorage(anyString()))
                .thenThrow(new RuntimeException("VIEW DATABASE STATE denied"));

        service.buildSnapshot(null, "workcube_mikrolink");

        assertThat(phaseTimer("storage")).isNotNull();
        assertThat(phaseTimer("storage").count()).isEqualTo(1);
    }

    private static CatalogSourceRegistry registryOf(SchemaExtractService reader) {
        when(reader.sourceId()).thenReturn(CatalogSourceRegistry.PRIMARY_SOURCE_ID);
        when(reader.engine()).thenReturn("mssql");
        return new CatalogSourceRegistry(java.util.List.of(reader));
    }

    /**
     * gitops#3631 (Codex 01a08a98 P2): the row-count pass rebuilds every TableInfo. Before the
     * fix it used the five-field constructor and silently dropped the object comment the
     * reader had just read — for every table, as soon as one row count existed. A view with
     * a comment and a different table with a count must both keep what they had.
     */
    @Test
    void rowCountEnrichmentKeepsTheObjectComment() {
        var view = new com.example.schema.model.TableInfo("TRYPE_ALL_VOUCHER_QRY", "IFSAPP",
                List.of(new com.example.schema.model.ColumnInfo("COMPANY", "VARCHAR2", 20, false, false, true, 1)),
                null, 1, "Voucher rows across all voucher types");
        var table = new com.example.schema.model.TableInfo("TOAD_PLAN_TABLE", "IFSAPP", List.of(), null, 0, null);
        when(extract.extractTables(anyString())).thenReturn(Map.of(view.name(), view, table.name(), table));
        when(extract.getRowCounts(anyString())).thenReturn(Map.of("TOAD_PLAN_TABLE", 0L));

        SchemaSnapshot snap = service.buildSnapshot(null, "IFSAPP");

        assertThat(snap.tables().get("TRYPE_ALL_VOUCHER_QRY").comment())
                .as("row-count rebuild must not drop the comment")
                .isEqualTo("Voucher rows across all voucher types");
        assertThat(snap.tables().get("TRYPE_ALL_VOUCHER_QRY").rowCount()).isNull();
        assertThat(snap.tables().get("TOAD_PLAN_TABLE").rowCount()).isZero();
        assertThat(snap.tables().get("TOAD_PLAN_TABLE").comment()).isNull();
    }

    @Test
    void extractObjectsThrows_snapshotStillBuilt_objectsEmpty() {
        when(extract.extractObjects(anyString()))
                .thenThrow(new RuntimeException("sys.objects unavailable"));

        SchemaSnapshot snap = service.buildSnapshot(null, "workcube_mikrolink");

        assertThat(snap).isNotNull();
        assertThat(snap.objects()).isEmpty();
    }

    @Test
    void extractObjectsSucceeds_objectsCarriedIntoSnapshot() {
        ObjectInfo obj = new ObjectInfo(
                "INVOICE", "dbo", "USER_TABLE", 100, "dbo",
                LocalDateTime.of(2020, 1, 1, 10, 0),
                LocalDateTime.of(2021, 6, 15, 14, 30), Map.of());
        when(extract.extractObjects(anyString())).thenReturn(List.of(obj));

        SchemaSnapshot snap = service.buildSnapshot(null, "workcube_mikrolink");

        assertThat(snap.objects()).containsExactly(obj);
    }

    @Test
    void extractStorageThrows_snapshotStillBuilt_storageEmpty() {
        // sys.dm_db_partition_stats needs VIEW DATABASE STATE; a permission
        // failure must not collapse the snapshot — storage stays empty.
        when(extract.extractStorage(anyString()))
                .thenThrow(new RuntimeException("VIEW DATABASE STATE denied"));

        SchemaSnapshot snap = service.buildSnapshot(null, "workcube_mikrolink");

        assertThat(snap).isNotNull();
        assertThat(snap.storage()).isEmpty();
    }

    @Test
    void extractChangeDataThrows_snapshotStillBuilt_changeDataEmpty() {
        when(extract.extractChangeData(anyString()))
                .thenThrow(new RuntimeException("sys.change_tracking_tables unavailable"));

        SchemaSnapshot snap = service.buildSnapshot(null, "workcube_mikrolink");

        assertThat(snap).isNotNull();
        assertThat(snap.changeData()).isEmpty();
    }

    @Test
    void extractDatabaseOptionsThrows_snapshotStillBuilt_databaseOptionsNull() {
        when(extract.extractDatabaseOptions())
                .thenThrow(new RuntimeException("sys.databases not visible"));

        SchemaSnapshot snap = service.buildSnapshot(null, "workcube_mikrolink");

        assertThat(snap).isNotNull();
        assertThat(snap.databaseOptions()).isNull();
    }

    @Test
    void extractTablesBaseFailure_throwsSnapshotUnavailable() {
        // Q3 (Codex 019e335c): step-1 base extraction is the ONE fatal path.
        // After P1, extractTables throws only on base-extraction failure
        // (enrichment is non-fatal). buildSnapshot must surface that as the
        // domain exception SchemaExceptionHandler maps to HTTP 503 — never a
        // silent degrade and never a generic 500.
        when(extract.extractTables(anyString()))
                .thenThrow(new RuntimeException("base extraction down"));

        assertThatThrownBy(() -> service.buildSnapshot(null, "workcube_mikrolink"))
                .isInstanceOf(SnapshotUnavailableException.class)
                .hasMessageContaining("workcube_mikrolink")
                .cause().hasMessageContaining("base extraction down");
    }

    /**
     * metadata.dbType must be the engine of the reader that produced the
     * snapshot. It was the literal "mssql" for every source, so an IFS Oracle
     * snapshot introduced itself as MSSQL (measured live after #1143, whose
     * commit message claimed the fix but whose diff did not contain it).
     */
    @Test
    void metadataDbTypeIsTheReadersEngine() {
        when(extract.engine()).thenReturn("oracle");

        SchemaSnapshot snap = service.buildSnapshot(null, "IFSAPP");

        assertThat(snap.metadata().dbType()).isEqualTo("oracle");
    }
}
