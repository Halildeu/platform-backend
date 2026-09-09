package com.example.schema.service;

import com.example.schema.catalog.CatalogReader;
import com.example.schema.catalog.CatalogSourceRegistry;
import com.example.schema.model.SchemaSnapshot;
import com.example.schema.service.discovery.RelationshipDiscoveryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * platform-backend#1149 (Codex 01a08856) — through the real cache proxy
 * ({@code @EnableCaching} + {@link ConcurrentMapCacheManager}, the same
 * {@code spring.cache.type: simple} the service runs with): the warm-up's build
 * is the entry the Explorer's default lane hits; two concurrent callers of one
 * key build once ({@code sync=true}); a failed build is not cached; sources stay
 * apart.
 */
class SnapshotWarmupCacheTest {

    /** The cached method with a controllable body: counts builds, can block on a latch, can fail once. */
    static class CountingSnapshotService extends SchemaSnapshotService {
        final AtomicInteger builds = new AtomicInteger();
        volatile CountDownLatch holdBuild;
        volatile boolean failNext;

        CountingSnapshotService(CatalogSourceRegistry sources) {
            super(sources, mock(RelationshipDiscoveryService.class), mock(DomainClusteringService.class));
        }

        @Override
        public SchemaSnapshot buildSnapshot(String source, String schema) {
            builds.incrementAndGet();
            CountDownLatch latch = holdBuild;
            if (latch != null) {
                try {
                    latch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("catalog unavailable");
            }
            return SchemaSnapshot.builder()
                    .version("v1")
                    .metadata(new SchemaSnapshot.Metadata("x", "h", "d", schema, Instant.now(), 0, 0, 0, 0))
                    .tables(Map.of())
                    .relationships(List.of())
                    .domains(Map.of())
                    .analysis(new SchemaSnapshot.Analysis(List.of(), List.of()))
                    .build();
        }
    }

    @Configuration
    @EnableCaching
    static class Config {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("snapshot");
        }

        @Bean
        CatalogSourceRegistry catalogSourceRegistry() {
            return new CatalogSourceRegistry(List.of(reader("workcube", "workcube_mikrolink"), reader("ifs", "IFSAPP")));
        }

        @Bean
        CountingSnapshotService schemaSnapshotService(CatalogSourceRegistry registry) {
            return new CountingSnapshotService(registry);
        }
    }

    private static CatalogReader reader(String id, String schema) {
        CatalogReader r = mock(CatalogReader.class);
        when(r.sourceId()).thenReturn(id);
        when(r.engine()).thenReturn("x");
        when(r.defaultSchema()).thenReturn(schema);
        return r;
    }

    private AnnotationConfigApplicationContext ctx;
    private CountingSnapshotService target;
    private SchemaSnapshotService proxied;
    private CatalogSourceRegistry registry;

    @BeforeEach
    void start() {
        ctx = new AnnotationConfigApplicationContext(Config.class);
        proxied = ctx.getBean(SchemaSnapshotService.class);
        // the bean is a CGLIB cache proxy whose fields are never initialised; count on the real target
        target = (CountingSnapshotService) org.springframework.aop.framework.AopProxyUtils.getSingletonTarget(proxied);
        assertThat(target).isNotSameAs(proxied);
        registry = ctx.getBean(CatalogSourceRegistry.class);
    }

    @AfterEach
    void stop() {
        ctx.close();
    }

    @Test
    void warmUpEntryIsTheOneTheDefaultLaneHits() {
        new SnapshotWarmup(registry, proxied, true, Runnable::run).onReady();
        assertThat(target.builds.get()).as("one build per source").isEqualTo(2);

        proxied.buildSnapshot(null, "workcube_mikrolink");   // Explorer default lane: no source named
        proxied.buildSnapshot("", "workcube_mikrolink");
        proxied.buildSnapshot(" WorkCube ", "workcube_mikrolink");
        proxied.buildSnapshot("IFS", "IFSAPP");

        assertThat(target.builds.get()).as("all served from the warm entries").isEqualTo(2);
    }

    @Test
    void concurrentCallersOfOneKeyBuildOnce() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        target.holdBuild = release;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<SchemaSnapshot> warm = pool.submit(() -> proxied.buildSnapshot("workcube", "workcube_mikrolink"));
            Future<SchemaSnapshot> user = pool.submit(() -> proxied.buildSnapshot(null, "workcube_mikrolink"));
            Thread.sleep(200);                         // both callers are in, one is building
            target.holdBuild = null;
            release.countDown();

            assertThat(warm.get(5, TimeUnit.SECONDS)).isSameAs(user.get(5, TimeUnit.SECONDS));
            assertThat(target.builds.get()).as("sync=true: the second caller waited for the first build").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failedBuildIsNotCachedAndIsRetried() {
        target.failNext = true;
        new SnapshotWarmup(registry, proxied, true, Runnable::run).onReady();   // workcube fails, ifs builds
        assertThat(target.builds.get()).isEqualTo(2);

        proxied.buildSnapshot(null, "workcube_mikrolink");                        // rebuilds the failed one
        proxied.buildSnapshot("ifs", "IFSAPP");                                   // hit

        assertThat(target.builds.get()).isEqualTo(3);
    }

    @Test
    void sourcesAndSchemasKeepSeparateEntries() {
        proxied.buildSnapshot("ifs", "IFSAPP");
        proxied.buildSnapshot("ifs", "SYS");
        proxied.buildSnapshot("workcube", "workcube_mikrolink");
        proxied.buildSnapshot("ifs", "IFSAPP");

        assertThat(target.builds.get()).isEqualTo(3);
    }
}
