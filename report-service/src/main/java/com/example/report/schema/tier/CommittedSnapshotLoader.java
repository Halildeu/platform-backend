package com.example.report.schema.tier;

import com.example.report.schema.SchemaSnapshot;
import com.example.report.schema.SchemaTruthLookupContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Phase 2 Program 8b — Tier 2 committed snapshot loader.
 *
 * <p>Snapshot kaynağı:
 * <ul>
 *   <li>Default classpath: {@code classpath:schema/workcube-schema-fixture.json}
 *       (test fixture; production'da override edilir)</li>
 *   <li>Production: ENV {@code SCHEMA_SNAPSHOT_COMMITTED_PATH=/etc/report-service/workcube-schema.json}
 *       — ConfigMap volume mount'tan veya gitops repo'dan güncellenmiş dosya</li>
 * </ul>
 *
 * <p>Loaded once at startup ({@code @PostConstruct}); in-memory cache (no
 * 5-min TTL — committed snapshot zaten static state). Snapshot age (file
 * mtime) {@link #snapshotAgeDays(Clock)} ile sorgulanabilir; Phase 2
 * Program 1 RC kontrolü 30+ gün ise WARN üretir (Plan §3.8 Q4 default).
 *
 * <p>Spec: §2.1, §2.2 Tier 2.
 */
@Component
public class CommittedSnapshotLoader {

    private static final Logger log = LoggerFactory.getLogger(CommittedSnapshotLoader.class);

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final String snapshotPath;
    private final AtomicReference<SchemaSnapshot> cachedSnapshot = new AtomicReference<>();
    private final AtomicReference<Instant> snapshotMtime = new AtomicReference<>();

    public CommittedSnapshotLoader(ResourceLoader resourceLoader,
                                     ObjectMapper objectMapper,
                                     @Value("${schema.snapshot.committed-path:classpath:schema/workcube-schema-fixture.json}") String snapshotPath) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.snapshotPath = snapshotPath;
    }

    @PostConstruct
    public void loadSnapshot() {
        try {
            Resource resource = resourceLoader.getResource(snapshotPath);
            if (!resource.exists()) {
                log.warn("Committed snapshot not found at path: {}", snapshotPath);
                return;
            }
            try (InputStream in = resource.getInputStream()) {
                SchemaSnapshot snapshot = objectMapper.readValue(in, SchemaSnapshot.class);
                cachedSnapshot.set(snapshot);
                Instant mtime = resolveMtime(resource);
                snapshotMtime.set(mtime);
                log.info("Committed snapshot loaded: path={} tables={} mtime={}",
                        snapshotPath, snapshot.tables().size(), mtime);
            }
        } catch (IOException e) {
            log.error("Failed to load committed snapshot from {}", snapshotPath, e);
        }
    }

    /**
     * Tier 2 lookup — in-memory cached snapshot.
     *
     * <p>Caller {@code SchemaTruthService} facade; build-time
     * ({@code BUILD_DETERMINISTIC}) primary tier; runtime
     * ({@code RUNTIME_DEGRADED_TYPE}) fallback tier (Tier 1 fail-soft sonrası).
     *
     * @param ctx          lookup context (logging için)
     * @param schemaName   schema adı — committed snapshot'ta `metadata.schema`
     *                     veya `tables[*].schema` ile match edilir
     * @return {@link Optional#empty()} if snapshot loaded değilse veya schema
     *         mevcut değilse; {@link Optional#of} otherwise
     */
    public Optional<SchemaSnapshot> lookup(SchemaTruthLookupContext ctx, String schemaName) {
        SchemaSnapshot snapshot = cachedSnapshot.get();
        if (snapshot == null) {
            log.debug("Committed snapshot not loaded; Tier 2 returns empty for schema={}", schemaName);
            return Optional.empty();
        }
        // Snapshot single-schema: tüm tables aynı schema (committed dump).
        // Eğer requested schema'nın bir table'ı varsa, snapshot'ı dön.
        boolean schemaMatch = snapshot.tables().values().stream()
                .anyMatch(t -> schemaName != null && schemaName.equals(t.schema()));
        if (!schemaMatch) {
            log.debug("Committed snapshot does not contain schema={} (consumer={})",
                    schemaName, ctx != null ? ctx.consumer() : "unknown");
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    /**
     * Snapshot file age in days, için Phase 2 Program 1 RC-snapshot-age WARN.
     *
     * @param clock injectable clock (deterministic test için)
     * @return {@link Optional#empty()} mtime resolve edilemediyse;
     *         days otherwise
     */
    public Optional<Long> snapshotAgeDays(Clock clock) {
        Instant mtime = snapshotMtime.get();
        if (mtime == null) {
            return Optional.empty();
        }
        Duration age = Duration.between(mtime, clock.instant());
        return Optional.of(age.toDays());
    }

    private Instant resolveMtime(Resource resource) {
        try {
            // Resource API mtime sağlamayabilir (classpath JAR resource için).
            // File-system path'te (URL → Path) Files.getLastModifiedTime kullan.
            if (resource.isFile()) {
                Path path = resource.getFile().toPath();
                return Files.getLastModifiedTime(path).toInstant();
            }
            // JAR / classpath resource için resource.lastModified() millis döner.
            long lastModified = resource.lastModified();
            if (lastModified > 0) {
                return Instant.ofEpochMilli(lastModified);
            }
        } catch (IOException ignored) {
            // mtime resolve edilemedi; null kalır → snapshotAgeDays empty
        }
        return null;
    }
}
