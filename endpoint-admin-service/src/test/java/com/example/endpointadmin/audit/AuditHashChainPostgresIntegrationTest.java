package com.example.endpointadmin.audit;

import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.model.EndpointAuditEvent;
import com.example.endpointadmin.repository.EndpointAuditEventRepository;
import com.example.endpointadmin.service.EndpointAuditService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-016 — Postgres-only integration tests for the audit hash-chain
 * (Codex 019e4f8e). Verifies the parts that H2 cannot exercise:
 * <ul>
 *   <li>Flyway V4 migration applies cleanly on a real Postgres engine;</li>
 *   <li>the {@code endpoint_audit_events} append-only trigger rejects direct
 *       {@code UPDATE} and {@code DELETE};</li>
 *   <li>the real {@link PgAdvisoryAuditChainLock} (pg_advisory_xact_lock) works
 *       and the chain write path produces a verifiable chain on Postgres.</li>
 * </ul>
 *
 * <p>Uses the genuine {@link PgAdvisoryAuditChainLock}, NOT the H2 no-op — the
 * advisory lock must be exercised against a real Postgres backend.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        TimeConfig.class,
        EndpointAuditService.class,
        PgAdvisoryAuditChainLock.class,
        AuditIntegrityVerifier.class
})
class AuditHashChainPostgresIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("endpoint_admin")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Flyway owns the schema on the real Postgres engine.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "public");
    }

    private static final UUID TENANT = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @Autowired
    private EndpointAuditService auditService;

    @Autowired
    private AuditIntegrityVerifier verifier;

    @Autowired
    private EndpointAuditEventRepository auditRepository;

    @Autowired
    private EntityManager entityManager;

    private EndpointAuditEvent record(String eventType) {
        return auditService.record(TENANT, null, null, eventType,
                "TEST_ACTION", "subject@test", "corr-" + eventType,
                Map.of("event", eventType), null, null);
    }

    @Test
    void flywayV4MigrationAppliedHashColumnsPresent() {
        // If V4 applied, these columns exist; a select referencing them succeeds.
        Object result = entityManager.createNativeQuery(
                        "SELECT count(*) FROM endpoint_audit_events "
                                + "WHERE event_hash IS NULL OR prev_event_hash IS NULL "
                                + "OR event_hash_alg IS NULL OR event_hash_version IS NULL")
                .getSingleResult();
        assertThat(result).isNotNull();
    }

    @Test
    void chainWritePathProducesVerifiableChainOnPostgres() {
        record("PG_EVENT_1");
        record("PG_EVENT_2");
        record("PG_EVENT_3");
        entityManager.flush();
        AuditIntegrityVerifier.Result result = verifier.verifyTenant(TENANT);
        assertThat(result.valid()).isTrue();
        assertThat(result.checkedCount()).isEqualTo(3);
    }

    @Test
    void appendOnlyTriggerRejectsDirectUpdate() {
        EndpointAuditEvent event = record("PG_UPDATE_TARGET");
        entityManager.flush();
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                            "UPDATE endpoint_audit_events SET action = 'TAMPERED' WHERE id = :id")
                    .setParameter("id", event.getId())
                    .executeUpdate();
            entityManager.flush();
        }).hasMessageContaining("append-only");
    }

    @Test
    void appendOnlyTriggerRejectsDirectDelete() {
        EndpointAuditEvent event = record("PG_DELETE_TARGET");
        entityManager.flush();
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                            "DELETE FROM endpoint_audit_events WHERE id = :id")
                    .setParameter("id", event.getId())
                    .executeUpdate();
            entityManager.flush();
        }).hasMessageContaining("append-only");
    }

    @Test
    void advisoryLockAcquiresWithoutError() {
        // pg_advisory_xact_lock must succeed against the real engine; the H2
        // no-op cannot prove this.
        record("PG_LOCK_PROBE");
        entityManager.flush();
        AuditIntegrityVerifier.Result result = verifier.verifyTenant(TENANT);
        assertThat(result.valid()).isTrue();
        assertThat(result.checkedCount()).isEqualTo(1);
    }
}
