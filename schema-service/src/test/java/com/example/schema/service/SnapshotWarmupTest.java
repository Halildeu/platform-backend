package com.example.schema.service;

import com.example.schema.catalog.CatalogReader;
import com.example.schema.catalog.CatalogSourceRegistry;
import com.example.schema.model.SchemaSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * platform-backend#1149 — after start-up every source's default-schema snapshot
 * is built through the same service (and cache key) the requests use; a failing
 * source does not stop the others; the switch turns it off entirely.
 */
class SnapshotWarmupTest {

    private static CatalogReader reader(String id, String schema) {
        CatalogReader r = mock(CatalogReader.class);
        when(r.sourceId()).thenReturn(id);
        when(r.engine()).thenReturn("x");
        when(r.defaultSchema()).thenReturn(schema);
        return r;
    }

    private static SchemaSnapshot snapshot() {
        return SchemaSnapshot.builder()
                .version("v1")
                .metadata(new SchemaSnapshot.Metadata("mssql", "h", "d", "s", Instant.now(), 0, 0, 0, 0))
                .tables(Map.of())
                .relationships(List.of())
                .domains(Map.of())
                .analysis(new SchemaSnapshot.Analysis(List.of(), List.of()))
                .build();
    }

    private final CatalogSourceRegistry registry = new CatalogSourceRegistry(
            List.of(reader(CatalogSourceRegistry.PRIMARY_SOURCE_ID, "workcube_mikrolink"), reader("ifs", "IFSAPP")));
    private final SchemaSnapshotService snapshots = mock(SchemaSnapshotService.class);

    @Test
    void buildsEverySourcesDefaultSchemaInRegistrationOrder() {
        when(snapshots.buildSnapshot(anyString(), anyString())).thenReturn(snapshot());
        SnapshotWarmup warmup = new SnapshotWarmup(registry, snapshots, true, Runnable::run);

        warmup.onReady();

        InOrder order = inOrder(snapshots);
        order.verify(snapshots).buildSnapshot("workcube", "workcube_mikrolink");
        order.verify(snapshots).buildSnapshot("ifs", "IFSAPP");
    }

    @Test
    void aFailingSourceDoesNotStopTheNextOne() {
        when(snapshots.buildSnapshot("workcube", "workcube_mikrolink")).thenThrow(new IllegalStateException("mssql down"));
        when(snapshots.buildSnapshot("ifs", "IFSAPP")).thenReturn(snapshot());
        SnapshotWarmup warmup = new SnapshotWarmup(registry, snapshots, true, Runnable::run);

        warmup.onReady();

        verify(snapshots).buildSnapshot("ifs", "IFSAPP");
    }

    @Test
    void disabledSwitchBuildsNothing() {
        SnapshotWarmup warmup = new SnapshotWarmup(registry, snapshots, false, Runnable::run);

        warmup.onReady();

        verify(snapshots, never()).buildSnapshot(any(), any());
    }

    @Test
    void productionConstructorDoesNotBlockTheListenerAndUsesADaemonWorker() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> worker = new java.util.concurrent.atomic.AtomicReference<>();
        when(snapshots.buildSnapshot(anyString(), anyString())).thenAnswer(inv -> {
            worker.set(Thread.currentThread());
            entered.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return snapshot();
        });
        SnapshotWarmup warmup = new SnapshotWarmup(registry, snapshots, true);

        long t0 = System.nanoTime();
        warmup.onReady();
        long listenerMs = (System.nanoTime() - t0) / 1_000_000;

        org.assertj.core.api.Assertions.assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        org.assertj.core.api.Assertions.assertThat(listenerMs).as("ApplicationReadyEvent listener returns at once").isLessThan(1000);
        org.assertj.core.api.Assertions.assertThat(worker.get()).isNotSameAs(Thread.currentThread());
        org.assertj.core.api.Assertions.assertThat(worker.get().isDaemon()).isTrue();
        org.assertj.core.api.Assertions.assertThat(worker.get().getName()).isEqualTo("snapshot-warmup");
        release.countDown();
    }

    @Test
    void runsOffTheCallingThread() {
        when(snapshots.buildSnapshot(anyString(), anyString())).thenReturn(snapshot());
        List<Runnable> submitted = new java.util.ArrayList<>();
        SnapshotWarmup warmup = new SnapshotWarmup(registry, snapshots, true, submitted::add);

        warmup.onReady();

        verify(snapshots, never()).buildSnapshot(any(), any());
        submitted.forEach(Runnable::run);
        verify(snapshots).buildSnapshot("workcube", "workcube_mikrolink");
    }
}
