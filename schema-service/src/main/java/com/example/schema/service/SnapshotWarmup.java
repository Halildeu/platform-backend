package com.example.schema.service;

import com.example.schema.catalog.CatalogReader;
import com.example.schema.catalog.CatalogSourceRegistry;
import com.example.schema.model.SchemaSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * Builds every source's default-schema snapshot right after start-up
 * (platform-backend#1149).
 *
 * <p>Measured on testai: after each rollout the first {@code /snapshot} for
 * Workcube took 106 s and for IFS 37 s, during which the Explorer showed only a
 * spinner and the browser acceptance lane timed out. The catalogue cache is
 * per pod, so a fresh pod always started cold. The warm-up runs the same
 * {@link SchemaSnapshotService#buildSnapshot} the requests use (same cache
 * key), sequentially on one daemon thread so the two heavy dictionary reads do
 * not contend, and never blocks readiness: a request that arrives first simply
 * builds (or waits for) the same entry as before.
 */
@Component
public class SnapshotWarmup {

    private static final Logger log = LoggerFactory.getLogger(SnapshotWarmup.class);

    private final CatalogSourceRegistry sources;
    private final SchemaSnapshotService snapshots;
    private final boolean enabled;
    private final Executor executor;

    @Autowired
    public SnapshotWarmup(CatalogSourceRegistry sources,
                          SchemaSnapshotService snapshots,
                          @Value("${schema.snapshot.warmup.enabled:true}") boolean enabled) {
        this(sources, snapshots, enabled, task -> {
            Thread t = new Thread(task, "snapshot-warmup");
            t.setDaemon(true);
            t.start();
        });
    }

    SnapshotWarmup(CatalogSourceRegistry sources, SchemaSnapshotService snapshots,
                   boolean enabled, Executor executor) {
        this.sources = sources;
        this.snapshots = snapshots;
        this.enabled = enabled;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            log.info("Snapshot warm-up disabled (schema.snapshot.warmup.enabled=false)");
            return;
        }
        executor.execute(this::warmAll);
    }

    /** One pass over every registered source; a failure is logged and the next source still runs. */
    void warmAll() {
        for (CatalogReader reader : sources.readers()) {
            String source = reader.sourceId();
            String schema = reader.defaultSchema();
            long started = System.nanoTime();
            try {
                SchemaSnapshot snapshot = snapshots.buildSnapshot(source, schema);
                log.info("Snapshot warm-up: source={} schema={} tables={} in {} ms",
                        source, schema, snapshot.tables().size(), (System.nanoTime() - started) / 1_000_000);
            } catch (RuntimeException e) {
                log.warn("Snapshot warm-up failed: source={} schema={} after {} ms: {}",
                        source, schema, (System.nanoTime() - started) / 1_000_000, e.toString());
            }
        }
    }
}
