package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointSoftwareInventoryStateHistory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BE-024 — race-safe + atomic write path for the append-only software-state
 * history (Codex 019e75fe CRITICAL fix).
 *
 * <p>Replaces the previous broad {@code catch (DataIntegrityViolationException)}
 * in {@code EndpointSoftwareInventoryService.appendStateHistory}. Swallowing
 * every {@code DataIntegrityViolationException} (a) mis-classified a
 * non-duplicate V18 violation (a CHECK / FK breach) as a "duplicate" and hid a
 * data bug, and (b) on PostgreSQL left the surrounding transaction marked
 * rollback-only, so the later audit/commit stage of {@code ingest} failed
 * uncontrolled — breaking the snapshot+result+history atomicity claim.
 *
 * <h3>PostgreSQL (CI-authoritative + production)</h3>
 *
 * <p>A native {@code INSERT ... ON CONFLICT (source_command_result_id)
 * WHERE source_command_result_id IS NOT NULL DO NOTHING}. The conflict target
 * repeats the V18 partial-index predicate so PG infers the <em>partial</em>
 * unique index {@code uq_endpoint_software_inventory_state_history_source_cmd_result}.
 * Consequences:
 * <ul>
 *   <li>A duplicate non-null {@code source_command_result_id} → {@code DO
 *       NOTHING} → 0 rows, no exception, transaction stays clean.</li>
 *   <li>A {@code NULL} {@code source_command_result_id} (manual/test path) is
 *       outside the partial index, so it never conflicts and always
 *       inserts.</li>
 *   <li>ANY other violation — the {@code schema_version} / {@code app_count}
 *       range CHECKs, the {@code apps_digest_hash} regex CHECK, the
 *       {@code jsonb_typeof(apps_digest)='array'} CHECK, or the composite
 *       device FK — is NOT the conflict target, so it propagates as a
 *       {@code DataIntegrityViolationException} and rolls the whole ingest
 *       transaction back together with the snapshot + result.</li>
 * </ul>
 *
 * <h3>Non-PostgreSQL (H2 unit/slice tests)</h3>
 *
 * <p>H2 — even in {@code MODE=PostgreSQL} — supports neither partial unique
 * indexes nor the {@code ON CONFLICT ... DO NOTHING} grammar, and the partial
 * unique index does not exist on the Hibernate-generated H2 schema (it lives
 * only in the V18 Flyway migration). The H2 slice has no concurrency, so this
 * path persists through the {@link EntityManager} (Hibernate assigns the
 * {@code @GeneratedValue} id + runs {@code @PrePersist}); the service-level
 * pre-probe is the idempotency guard there. Every constraint violation still
 * propagates (no swallow). Mirrors the dialect-aware fallback already used by
 * {@code EndpointComplianceService.resolveIsPostgresDialect()}
 * (Codex 019e6bdf precedent).
 */
public class EndpointSoftwareInventoryStateHistoryRepositoryImpl
        implements EndpointSoftwareInventoryStateHistoryRepositoryCustom {

    private static final Logger log = LoggerFactory.getLogger(
            EndpointSoftwareInventoryStateHistoryRepositoryImpl.class);

    private static final String TABLE =
            "endpoint_software_inventory_state_history";

    /** Local, config-free mapper: the digest is built from already-sanitized
     *  String / null values, so default serialization is deterministic and
     *  needs no Spring-managed {@code ObjectMapper} bean (which the
     *  {@code @DataJpaTest} slice does not provide). */
    private static final ObjectMapper DIGEST_MAPPER = new ObjectMapper();

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${spring.jpa.properties.hibernate.default_schema:endpoint_admin_service}")
    private String schema;

    private volatile Boolean isPostgresDialect;

    @Override
    @Transactional
    public boolean insertIfNewSourceCommandResult(
            EndpointSoftwareInventoryStateHistory history) {
        if (resolveIsPostgresDialect()) {
            return insertOnConflictPostgres(history);
        }
        // Non-Postgres (H2 slice): no partial index, no concurrency — persist
        // through Hibernate so @GeneratedValue id + @PrePersist run. The
        // service pre-probe is the idempotency guard; any constraint
        // violation still propagates (no swallow).
        entityManager.persist(history);
        entityManager.flush();
        return true;
    }

    private boolean insertOnConflictPostgres(
            EndpointSoftwareInventoryStateHistory history) {
        // The native insert bypasses Hibernate's @GeneratedValue id +
        // @PrePersist createdAt default, so assign both here (the entity is
        // never persisted through the EntityManager on this branch).
        java.util.UUID id = history.getId() != null
                ? history.getId() : java.util.UUID.randomUUID();
        Instant createdAt = history.getCreatedAt() != null
                ? history.getCreatedAt() : Instant.now();

        String table = qualified(TABLE);
        // The ON CONFLICT clause repeats the V18 partial-index predicate
        // (WHERE source_command_result_id IS NOT NULL) so PG infers the
        // PARTIAL unique index. Without it PG raises "ON CONFLICT does not
        // match any unique/exclusion constraint". CAST(... AS jsonb) binds
        // the apps_digest text as JSONB; the V18 jsonb_typeof CHECK still
        // applies (a non-array would propagate).
        String sql = """
                INSERT INTO %s
                    (id, tenant_id, device_id, source_command_result_id,
                     schema_version, app_count, apps_digest_hash, apps_digest,
                     captured_at, created_at)
                VALUES
                    (:id, :tenantId, :deviceId, :sourceCommandResultId,
                     :schemaVersion, :appCount, :appsDigestHash,
                     CAST(:appsDigest AS jsonb),
                     :capturedAt, :createdAt)
                ON CONFLICT (source_command_result_id)
                    WHERE source_command_result_id IS NOT NULL
                    DO NOTHING
                """.formatted(table);

        int inserted = entityManager.createNativeQuery(sql)
                .setParameter("id", id)
                .setParameter("tenantId", history.getTenantId())
                .setParameter("deviceId", history.getDeviceId())
                .setParameter("sourceCommandResultId",
                        history.getSourceCommandResultId())
                .setParameter("schemaVersion", history.getSchemaVersion())
                .setParameter("appCount", history.getAppCount())
                .setParameter("appsDigestHash", history.getAppsDigestHash())
                .setParameter("appsDigest", serializeDigest(history.getAppsDigest()))
                .setParameter("capturedAt", history.getCapturedAt())
                .setParameter("createdAt", createdAt)
                .executeUpdate();
        return inserted > 0;
    }

    private String serializeDigest(List<Map<String, Object>> digest) {
        try {
            return DIGEST_MAPPER.writeValueAsString(
                    digest == null ? List.of() : digest);
        } catch (JsonProcessingException ex) {
            // The digest is built from already-sanitized String/primitive
            // values, so this is not expected at runtime.
            throw new IllegalStateException(
                    "BE-024 apps_digest JSON serialization failed.", ex);
        }
    }

    /**
     * Lazy single-call cached probe (mirrors
     * {@code EndpointComplianceService.resolveIsPostgresDialect()}, Codex
     * 019e6bdf). Returns {@code true} only on a genuine Postgres engine; H2 /
     * unknown engines return {@code false} so the portable Hibernate persist
     * path runs. A metadata probe failure defaults to {@code false} (the
     * portable path is always safe).
     */
    private boolean resolveIsPostgresDialect() {
        Boolean cached = isPostgresDialect;
        if (cached != null) {
            return cached;
        }
        boolean detected = false;
        try {
            org.hibernate.Session session =
                    entityManager.unwrap(org.hibernate.Session.class);
            String product = session.doReturningWork(connection ->
                    connection.getMetaData().getDatabaseProductName());
            if (product != null) {
                detected = product.toLowerCase(Locale.ROOT).contains("postgres");
            }
        } catch (RuntimeException ex) {
            log.debug("BE-024 could not resolve database dialect — "
                    + "defaulting to non-Postgres", ex);
        }
        isPostgresDialect = detected;
        return detected;
    }

    private String qualified(String tableName) {
        String resolvedSchema = schema == null ? "" : schema.trim();
        if (resolvedSchema.isBlank()) {
            return tableName;
        }
        if (!resolvedSchema.matches("[A-Za-z0-9_]+")) {
            throw new IllegalStateException(
                    "Invalid endpoint admin schema name.");
        }
        return resolvedSchema + "." + tableName;
    }
}
