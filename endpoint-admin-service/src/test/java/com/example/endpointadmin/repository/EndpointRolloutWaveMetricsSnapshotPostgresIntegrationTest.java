package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointRolloutWaveMetricsSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** §9.3 V61 snapshot invariants on real PG: deterministic latest, append-only, denominator CHECKs. */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointRolloutWaveMetricsSnapshotPostgresIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("endpoint_admin").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "public");
    }

    @Autowired EndpointRolloutWaveMetricsSnapshotRepository repository;
    @Autowired JdbcTemplate jdbc;

    private final UUID tenant = UUID.randomUUID();

    private UUID save(int wave, int fleet, int stale, Instant capturedAt) {
        EndpointRolloutWaveMetricsSnapshot s = new EndpointRolloutWaveMetricsSnapshot();
        s.setId(UUID.randomUUID());
        s.setTenantId(tenant);
        s.setRolloutId("rollout-x");
        s.setWaveId("wave-x");
        s.setActiveWaveSize(wave);
        s.setFleetSize(fleet);
        s.setStale24hCount(stale);
        s.setCapturedAt(capturedAt);
        return repository.saveAndFlush(s).getId();
    }

    @Test
    void latestSnapshotIsTheMostRecentlyCaptured() {
        save(40, 800, 5, Instant.parse("2026-06-09T08:00:00Z"));
        UUID newer = save(60, 800, 9, Instant.parse("2026-06-09T09:00:00Z"));
        var latest = repository.findFirstByTenantIdAndRolloutIdAndWaveIdOrderByCapturedAtDescCreatedAtDescIdDesc(
                tenant, "rollout-x", "wave-x").orElseThrow();
        assertThat(latest.getId()).isEqualTo(newer);
        assertThat(latest.getActiveWaveSize()).isEqualTo(60);
    }

    @Test
    void snapshotIsAppendOnly() {
        UUID id = save(50, 800, 10, Instant.parse("2026-06-09T09:00:00Z"));
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE endpoint_rollout_wave_metrics_snapshot SET fleet_size = 999 WHERE id = ?", id))
                .hasMessageContaining("append-only");
    }

    @Test
    void zeroActiveWaveSizeIsRejectedByCheck() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO endpoint_rollout_wave_metrics_snapshot
                  (id, tenant_id, org_id, rollout_id, wave_id, active_wave_size, fleet_size,
                   stale_24h_count, source_type, captured_at, created_at)
                VALUES (?, ?, ?, 'r', 'w', 0, 800, 0, 'orchestrator_snapshot', ?, ?)
                """, id, tenant, tenant, Timestamp.from(now), Timestamp.from(now)))
                .hasMessageContaining("ck_erwms_active_wave_size");
    }
}
