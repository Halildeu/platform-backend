package com.example.endpointadmin.service;

import com.example.endpointadmin.audit.PgAdvisoryAuditChainLock;
import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.dto.v1.agent.AgentHeartbeatRequest;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.security.DeviceCredentialResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * platform-backend#1206 against real PostgreSQL + Flyway: a committed heartbeat
 * of a device with no inventory queues exactly one full COLLECT_INVENTORY, the
 * AFTER_COMMIT listener runs its own transaction, retries follow the backoff,
 * everything stops once a snapshot exists, and concurrent callers cannot queue
 * a duplicate.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        TimeConfig.class,
        EndpointHeartbeatService.class,
        InitialInventoryAutoCollectService.class,
        InitialInventoryAutoCollectListener.class,
        EndpointAuditService.class,
        PgAdvisoryAuditChainLock.class
})
class InitialInventoryAutoCollectPostgresIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("endpoint_admin")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
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

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-1206-1206-1206-aaaaaaaaaaaa");
    private static final List<String> COLLECT_CAPABLE = List.of("COLLECT_INVENTORY", "LIST_LOCAL_USERS");

    @Autowired private EndpointHeartbeatService heartbeatService;
    @Autowired private InitialInventoryAutoCollectService autoCollectService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void firstHeartbeatQueuesOneFullCollect_andLaterHeartbeatsDoNotDuplicateIt() {
        UUID deviceId = seedDevice();

        heartbeat(deviceId, COLLECT_CAPABLE);
        heartbeat(deviceId, COLLECT_CAPABLE);
        heartbeat(deviceId, COLLECT_CAPABLE);

        List<Map<String, Object>> collects = collects(deviceId);
        assertThat(collects).hasSize(1);
        Map<String, Object> collect = collects.get(0);
        assertThat(collect.get("status")).isEqualTo("QUEUED");
        assertThat(collect.get("approval_status")).isEqualTo("NOT_REQUIRED");
        assertThat(collect.get("issued_by_subject")).isEqualTo("system:auto-initial-inventory");
        assertThat(collect.get("idempotency_key")).isEqualTo("auto-initial-inventory:" + deviceId + ":0");
        assertThat(collect.get("expires_at")).isNull();
        assertThat((String) collect.get("payload"))
                .contains("\"includeSoftware\": true", "\"includeHardware\": true",
                        "\"includeDeviceHealth\": true", "\"includeOutdatedSoftware\": true",
                        "\"includeHotfixPosture\": true", "\"includeDiagnostics\": true",
                        "\"includeServices\": true", "\"includeStartupExposure\": true",
                        "\"includeAppControl\": true", "\"includeSecurityNetwork\": true",
                        "\"includeWinGetEgress\": true");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM endpoint_audit_events WHERE command_id = ? "
                        + "AND action = 'AUTO_INITIAL_INVENTORY' "
                        + "AND performed_by_subject = 'system:auto-initial-inventory'",
                Integer.class, collect.get("id"))).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aDeviceWhoseInventoryAlreadyReachedTheSystemIsLeftAlone() {
        UUID deviceId = seedDevice();
        seedSnapshot(deviceId);

        heartbeat(deviceId, COLLECT_CAPABLE);

        assertThat(collects(deviceId)).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void anAgentThatCannotCollectIsLeftAlone() {
        UUID deviceId = seedDevice();

        heartbeat(deviceId, List.of("LIST_LOCAL_USERS"));

        assertThat(collects(deviceId)).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aFailedCollectIsRetriedAfterTheBackoff_untilTheFirstSnapshotLands() {
        UUID deviceId = seedDevice();
        heartbeat(deviceId, COLLECT_CAPABLE);
        UUID first = (UUID) collects(deviceId).get(0).get("id");

        settle(first, "FAILED", Duration.ofMinutes(5));
        heartbeat(deviceId, COLLECT_CAPABLE);
        assertThat(collects(deviceId)).as("5 min after the failure: still inside the 15 min backoff").hasSize(1);

        settle(first, "FAILED", Duration.ofMinutes(16));
        heartbeat(deviceId, COLLECT_CAPABLE);
        List<Map<String, Object>> collects = collects(deviceId);
        assertThat(collects).hasSize(2);
        assertThat(collects.get(1).get("idempotency_key")).isEqualTo("auto-initial-inventory:" + deviceId + ":1");
        assertThat(collects.get(1).get("status")).isEqualTo("QUEUED");

        UUID second = (UUID) collects.get(1).get("id");
        settle(second, "SUCCEEDED", Duration.ofDays(2));
        seedSnapshot(deviceId);
        heartbeat(deviceId, COLLECT_CAPABLE);
        assertThat(collects(deviceId)).as("the snapshot landed: never again").hasSize(2);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentCallersQueueExactlyOneCollect() throws Exception {
        UUID deviceId = seedDevice();
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<UUID>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return autoCollectService.enqueueIfNeeded(TENANT, deviceId);
                }));
            }
            start.countDown();
            int queued = 0;
            for (Future<Optional<UUID>> result : results) {
                if (result.get(60, TimeUnit.SECONDS).isPresent()) {
                    queued++;
                }
            }
            assertThat(queued).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(collects(deviceId)).hasSize(1);
    }

    private void heartbeat(UUID deviceId, List<String> capabilities) {
        heartbeatService.recordHeartbeat(
                new DeviceCredentialResult(deviceId.toString(), UUID.randomUUID().toString(), Instant.now()),
                new AgentHeartbeatRequest("install-1206", "PC-1206", OsType.WINDOWS, null, "amd64",
                        "v0.3.31", null, "Windows 11 Pro", "ONLINE", capabilities, Instant.now(),
                        Map.of(), List.of(), Map.of()),
                "10.0.0.5",
                "hmac");
    }

    private List<Map<String, Object>> collects(UUID deviceId) {
        return jdbc.queryForList(
                "SELECT id, status, approval_status, issued_by_subject, idempotency_key, expires_at, "
                        + "payload::text AS payload FROM endpoint_commands "
                        + "WHERE device_id = ? AND command_type = 'COLLECT_INVENTORY' ORDER BY issued_at",
                deviceId);
    }

    private void settle(UUID commandId, String status, Duration ago) {
        Timestamp at = Timestamp.from(Instant.now().minus(ago));
        jdbc.update("UPDATE endpoint_commands SET status = ?, completed_at = ?, updated_at = ? WHERE id = ?",
                status, at, at, commandId);
    }

    private UUID seedDevice() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, machine_fingerprint, status, "
                        + " os_type, os_version, agent_version, enrolled_at, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, 'ONLINE', 'WINDOWS', 'Windows 11', 'v0.3.31', ?, ?, ?, 0)",
                id, TENANT, TENANT, "host-" + id, "fp-" + id, now, now, now);
        return id;
    }

    private void seedSnapshot(UUID deviceId) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                "INSERT INTO endpoint_software_inventory_snapshots "
                        + "(id, tenant_id, device_id, schema_version, supported, app_count, truncated, "
                        + " apps_collected_at, apps_available, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 1, TRUE, 87, FALSE, ?, TRUE, ?, ?, 0)",
                UUID.randomUUID(), TENANT, deviceId, now, now, now);
    }
}
