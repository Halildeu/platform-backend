package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointStartupExposureApp;
import com.example.endpointadmin.model.EndpointStartupExposureProbeError;
import com.example.endpointadmin.model.EndpointStartupExposureSnapshot;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * BE — race-safe write path for the append-only startup-exposure
 * snapshot (Faz 22.5, AG-040-be). Mirrors AG-039-be
 * {@code EndpointServicesSnapshotRepositoryImpl} EXACTLY:
 * targetless {@code ON CONFLICT DO NOTHING} catches both partial
 * source_command_result_id UNIQUE + full (tenant, device, hash) UNIQUE
 * race-cleanly.
 *
 * <h3>PostgreSQL (CI-authoritative)</h3>
 *
 * <p>Native targetless INSERT ... ON CONFLICT DO NOTHING. Returns the
 * inserted id; null on no-op (caller MUST re-lookup winner).
 *
 * <h3>Non-PostgreSQL (H2)</h3>
 *
 * <p>Hibernate persist managed entity graph; service pre-probe is the
 * idempotency guard.
 */
public class EndpointStartupExposureSnapshotRepositoryImpl
        implements EndpointStartupExposureSnapshotRepositoryCustom {

    private static final Logger log = LoggerFactory.getLogger(
            EndpointStartupExposureSnapshotRepositoryImpl.class);

    private static final String SNAPSHOT_TABLE = "endpoint_startup_exposure_snapshots";
    private static final String APPS_TABLE = "endpoint_startup_exposure_apps";
    private static final String PROBE_ERRORS_TABLE = "endpoint_startup_exposure_probe_errors";

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${spring.jpa.properties.hibernate.default_schema:endpoint_admin_service}")
    private String schema;

    private volatile Boolean isPostgresDialect;

    @Override
    @Transactional
    public UUID insertStartupExposureSnapshotOnConflictDoNothing(EndpointStartupExposureSnapshot snapshot) {
        if (resolveIsPostgresDialect()) {
            return insertOnConflictPostgres(snapshot);
        }
        entityManager.persist(snapshot);
        entityManager.flush();
        return snapshot.getId();
    }

    private UUID insertOnConflictPostgres(EndpointStartupExposureSnapshot snapshot) {
        UUID id = snapshot.getId() != null ? snapshot.getId() : UUID.randomUUID();
        Instant now = Instant.now();
        Instant createdAt = snapshot.getCreatedAt() != null ? snapshot.getCreatedAt() : now;
        snapshot.setId(id);
        snapshot.setCreatedAt(createdAt);

        String table = qualified(SNAPSHOT_TABLE);
        String sql = """
                INSERT INTO %s
                    (id, tenant_id, device_id, source_command_result_id,
                     schema_version, supported, probe_complete,
                     rdp_enabled, windows_firewall_event_log_enabled,
                     probe_duration_ms, payload_hash_sha256,
                     collected_at, created_at)
                VALUES
                    (:id, :tenantId, :deviceId, :sourceCommandResultId,
                     :schemaVersion, :supported, :probeComplete,
                     :rdpEnabled, :windowsFirewallEventLogEnabled,
                     :probeDurationMs, :payloadHash,
                     :collectedAt, :createdAt)
                ON CONFLICT DO NOTHING
                """.formatted(table);

        int inserted = entityManager.createNativeQuery(sql)
                .setParameter("id", id)
                .setParameter("tenantId", snapshot.getTenantId())
                .setParameter("deviceId", snapshot.getDeviceId())
                .setParameter("sourceCommandResultId", snapshot.getSourceCommandResultId())
                .setParameter("schemaVersion", snapshot.getSchemaVersion())
                .setParameter("supported", snapshot.getSupported())
                .setParameter("probeComplete", snapshot.getProbeComplete())
                .setParameter("rdpEnabled", snapshot.getRdpEnabled())
                .setParameter("windowsFirewallEventLogEnabled", snapshot.getWindowsFirewallEventLogEnabled())
                .setParameter("probeDurationMs", snapshot.getProbeDurationMs())
                .setParameter("payloadHash", snapshot.getPayloadHashSha256())
                .setParameter("collectedAt", snapshot.getCollectedAt())
                .setParameter("createdAt", createdAt)
                .executeUpdate();

        if (inserted == 0) {
            return null;
        }

        insertAppsPostgres(snapshot, id);
        insertProbeErrorsPostgres(snapshot, id);
        return id;
    }

    private void insertAppsPostgres(EndpointStartupExposureSnapshot snapshot, UUID snapshotId) {
        List<EndpointStartupExposureApp> entries = snapshot.getStartupApps();
        if (entries == null || entries.isEmpty()) {
            return;
        }
        String table = qualified(APPS_TABLE);
        String sql = """
                INSERT INTO %s
                    (id, snapshot_id, tenant_id, row_ordinal, name, location, enabled, probe_origin)
                VALUES
                    (:id, :snapshotId, :tenantId, :rowOrdinal, :name, :location, :enabled, :probeOrigin)
                """.formatted(table);
        for (EndpointStartupExposureApp e : entries) {
            UUID rowId = e.getId() != null ? e.getId() : UUID.randomUUID();
            e.setId(rowId);
            entityManager.createNativeQuery(sql)
                    .setParameter("id", rowId)
                    .setParameter("snapshotId", snapshotId)
                    .setParameter("tenantId", snapshot.getTenantId())
                    .setParameter("rowOrdinal", e.getRowOrdinal())
                    .setParameter("name", e.getName())
                    .setParameter("location", e.getLocation())
                    .setParameter("enabled", e.getEnabled())
                    .setParameter("probeOrigin", e.getProbeOrigin())
                    .executeUpdate();
        }
    }

    private void insertProbeErrorsPostgres(EndpointStartupExposureSnapshot snapshot, UUID snapshotId) {
        List<EndpointStartupExposureProbeError> errs = snapshot.getProbeErrors();
        if (errs == null || errs.isEmpty()) {
            return;
        }
        String table = qualified(PROBE_ERRORS_TABLE);
        String sql = """
                INSERT INTO %s
                    (id, snapshot_id, tenant_id, row_ordinal, code, source, summary)
                VALUES
                    (:id, :snapshotId, :tenantId, :rowOrdinal, :code, :source, :summary)
                """.formatted(table);
        for (EndpointStartupExposureProbeError err : errs) {
            UUID rowId = err.getId() != null ? err.getId() : UUID.randomUUID();
            err.setId(rowId);
            entityManager.createNativeQuery(sql)
                    .setParameter("id", rowId)
                    .setParameter("snapshotId", snapshotId)
                    .setParameter("tenantId", snapshot.getTenantId())
                    .setParameter("rowOrdinal", err.getRowOrdinal())
                    .setParameter("code", err.getCode())
                    .setParameter("source", err.getSource())
                    .setParameter("summary", err.getSummary())
                    .executeUpdate();
        }
    }

    private boolean resolveIsPostgresDialect() {
        Boolean cached = isPostgresDialect;
        if (cached != null) return cached;
        boolean detected = false;
        try {
            org.hibernate.Session session = entityManager.unwrap(org.hibernate.Session.class);
            String product = session.doReturningWork(c -> c.getMetaData().getDatabaseProductName());
            if (product != null) {
                detected = product.toLowerCase(Locale.ROOT).contains("postgres");
            }
        } catch (RuntimeException ex) {
            log.debug("AG-040-be could not resolve database dialect — defaulting to non-Postgres", ex);
        }
        isPostgresDialect = detected;
        return detected;
    }

    private String qualified(String tableName) {
        String resolvedSchema = schema == null ? "" : schema.trim();
        if (resolvedSchema.isBlank()) return tableName;
        if (!resolvedSchema.matches("[A-Za-z0-9_]+")) {
            throw new IllegalStateException("Invalid endpoint admin schema name.");
        }
        return resolvedSchema + "." + tableName;
    }
}
