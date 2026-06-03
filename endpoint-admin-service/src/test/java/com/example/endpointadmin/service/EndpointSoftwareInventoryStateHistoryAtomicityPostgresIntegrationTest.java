package com.example.endpointadmin.service;

import com.example.endpointadmin.audit.NoOpAuditChainLock;
import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointSoftwareInventorySnapshot;
import com.example.endpointadmin.model.EndpointSoftwareInventoryStateHistory;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointSoftwareInventorySnapshotRepository;
import com.example.endpointadmin.repository.EndpointSoftwareInventoryStateHistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-024 — PostgreSQL-only atomicity tests for the software-state history
 * append (Codex 019e75fe CRITICAL). Proves the {@code ON CONFLICT
 * (source_command_result_id) WHERE source_command_result_id IS NOT NULL
 * DO NOTHING} write path against the REAL V18 partial-unique index:
 *
 * <ul>
 *   <li><b>(a) duplicate is a clean no-op</b> — at the repository layer a
 *       second insert with the same {@code source_command_result_id} returns
 *       {@code false}, throws nothing, and leaves a single row; at the service
 *       layer a duplicate re-ingest of the same command-result does NOT
 *       double-write the history.</li>
 *   <li><b>(b) a non-duplicate violation propagates + rolls back the whole
 *       transaction</b> — a history insert that breaches a NON-duplicate V18
 *       constraint (the {@code app_count >= 0} CHECK) throws a
 *       {@code DataIntegrityViolationException} (NOT swallowed, NOT
 *       mis-classified as a duplicate) and rolls back a companion snapshot
 *       write made in the same transaction. This is the exact regression the
 *       previous broad {@code catch (DataIntegrityViolationException)}
 *       allowed: it would have hidden the CHECK breach AND (on PG) left the
 *       transaction rollback-only so the later audit/commit stage failed
 *       uncontrolled.</li>
 * </ul>
 *
 * <p>The H2 {@code @DataJpaTest} slice ({@code EndpointSoftwareInventoryServiceTest})
 * cannot exercise either path: H2 has neither the partial unique index nor the
 * {@code ON CONFLICT ... DO NOTHING} grammar, and {@code ddl-auto=validate}
 * vs the real CHECK constraints only exist on Postgres. PG 16 Testcontainer +
 * Flyway + {@code public} schema (same setup as the V13 / V17 / V18 PG tests).
 * Runs in CI only — the local {@code -Dtest='!*PostgresIntegrationTest'}
 * filter skips it (Docker unavailable locally).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        TimeConfig.class,
        EndpointSoftwareInventoryService.class,
        EndpointAuditService.class,
        NoOpAuditChainLock.class,
        com.example.endpointadmin.security.SoftwareInventoryPayloadPolicy.class,
        com.example.endpointadmin.security.WinGetEgressPayloadPolicy.class
})
class EndpointSoftwareInventoryStateHistoryAtomicityPostgresIntegrationTest {

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
        registry.add("spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.jpa.properties.hibernate.default_schema",
                () -> "public");
    }

    private static final UUID TENANT =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String VALID_HASH = "a".repeat(64);

    @Autowired
    private EndpointSoftwareInventoryService service;

    @Autowired
    private EndpointSoftwareInventoryStateHistoryRepository historyRepository;

    @Autowired
    private EndpointSoftwareInventorySnapshotRepository snapshotRepository;

    @Autowired
    private EndpointDeviceRepository deviceRepository;

    @Autowired
    private EndpointCommandRepository commandRepository;

    @Autowired
    private EndpointCommandResultRepository resultRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    // ──────────────────────────────────────────────────────────────────
    // (a) duplicate source_command_result_id is a clean no-op
    // ──────────────────────────────────────────────────────────────────

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void repositoryDuplicateSourceCommandResultIsNoOpNotException() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        Fixture f = tx.execute(s -> newDeviceCommandResult());
        UUID resultId = f.resultId;

        // First insert lands.
        boolean first = tx.execute(s ->
                historyRepository.insertIfNewSourceCommandResult(
                        history(resultId, 1, 0, VALID_HASH, List.of())));
        assertThat(first).isTrue();

        // Second insert with the SAME source_command_result_id hits the V18
        // partial-UNIQUE index. ON CONFLICT DO NOTHING makes it a clean no-op:
        // returns false, throws NOTHING (the transaction is NOT marked
        // rollback-only).
        boolean second = tx.execute(s ->
                historyRepository.insertIfNewSourceCommandResult(
                        history(resultId, 1, 0, VALID_HASH, List.of())));
        assertThat(second).as("duplicate must be a no-op, not an insert")
                .isFalse();

        // Exactly one row for THIS source_command_result_id survived (the
        // duplicate was a no-op). Scope to the fixture — the per-class PG
        // container is shared and the prior NOT_SUPPORTED tests commit their
        // own rows, so a global count() would be polluted.
        Long forResult = tx.execute(s -> historyRepository
                .findBySourceCommandResultId(resultId).isPresent() ? 1L : 0L);
        assertThat(forResult).isEqualTo(1L);
        long forDevice = tx.execute(s -> historyRepository
                .findVisibleToOrgAndDeviceId(TENANT, f.deviceId,
                        org.springframework.data.domain.PageRequest.of(0, 10))
                .getTotalElements());
        assertThat(forDevice).as("no-op duplicate must not add a second row")
                .isEqualTo(1L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void serviceDuplicateFullIngestDoesNotDoubleWriteHistory() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        Fixture f = tx.execute(s -> newDeviceCommandResult());

        Map<String, Object> details = summaryWithApps(List.of(
                appMap("7-Zip", "24.07", "Igor Pavlov", "HKLM"),
                appMap("Notepad++", "8.6", "Don Ho", "HKLM_WOW6432")));

        // First ingest appends one history row.
        tx.executeWithoutResult(s -> service.ingest(
                reload(f.deviceId), command(f.commandId), result(f.resultId),
                details));
        // Re-ingest the SAME command-result (idempotent agent re-delivery):
        // the history append must no-op (pre-probe fast-path + ON CONFLICT),
        // not append a duplicate and not raise.
        tx.executeWithoutResult(s -> service.ingest(
                reload(f.deviceId), command(f.commandId), result(f.resultId),
                details));

        long rows = tx.execute(s -> historyRepository
                .findVisibleToOrgAndDeviceId(TENANT, f.deviceId,
                        org.springframework.data.domain.PageRequest.of(0, 10))
                .getTotalElements());
        assertThat(rows).as("idempotent re-ingest must not double-write")
                .isEqualTo(1L);
    }

    // ──────────────────────────────────────────────────────────────────
    // (b) a NON-duplicate violation propagates + rolls back the whole tx
    // ──────────────────────────────────────────────────────────────────

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void nonDuplicateViolationPropagatesAndRollsBackCompanionSnapshot() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        Fixture f = tx.execute(s -> newDeviceCommandResult());

        // One transaction: (1) a companion snapshot write succeeds, then
        // (2) a history insert that breaches the NON-duplicate
        // ck_..._app_count_range CHECK (app_count = -1). The whole tx must
        // roll back together — proving the violation is NOT swallowed and
        // does NOT leave the snapshot committed on its own.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            snapshotRepository.saveAndFlush(validSnapshot(reload(f.deviceId)));
            // Sanity: the snapshot is visible inside the tx before the failing
            // history insert.
            assertThat(snapshotRepository
                    .findByTenantIdAndDevice_Id(TENANT, f.deviceId))
                    .isPresent();
            historyRepository.insertIfNewSourceCommandResult(
                    history(f.resultId, 1, /* invalid */ -1, VALID_HASH,
                            List.of()));
        }))
                .isInstanceOf(DataIntegrityViolationException.class)
                // The real CHECK name surfaces — proving the violation is NOT
                // mis-classified/hidden as a "duplicate source_command_result".
                .hasMessageContaining(
                        "ck_endpoint_software_inventory_state_history_app_count_range");

        // After rollback: BOTH the companion snapshot AND any history row for
        // THIS fixture are gone — the tx rolled back atomically. Scope to the
        // fixture device (shared per-class container; sibling NOT_SUPPORTED
        // tests commit their own rows).
        TransactionTemplate verifyTx = new TransactionTemplate(txManager);
        boolean snapshotPresent = verifyTx.execute(s -> snapshotRepository
                .findByTenantIdAndDevice_Id(TENANT, f.deviceId).isPresent());
        assertThat(snapshotPresent)
                .as("companion snapshot must roll back with the failed history")
                .isFalse();
        long historyForDevice = verifyTx.execute(s -> historyRepository
                .findVisibleToOrgAndDeviceId(TENANT, f.deviceId,
                        org.springframework.data.domain.PageRequest.of(0, 10))
                .getTotalElements());
        assertThat(historyForDevice)
                .as("failed history insert must leave no row for the device")
                .isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void badHashViolationPropagatesNotSwallowed() {
        // A second non-duplicate breach class (the apps_digest_hash regex
        // CHECK) — defends against a future change that narrows the propagated
        // surface back to "duplicate only".
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Fixture f = tx.execute(s -> newDeviceCommandResult());

        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                historyRepository.insertIfNewSourceCommandResult(
                        history(f.resultId, 1, 0, "NOT_A_HASH", List.of()))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_endpoint_software_inventory_state_history_hash_format");

        // No row for THIS result was committed (scope to the fixture — shared
        // per-class container).
        boolean present = tx.execute(s -> historyRepository
                .findBySourceCommandResultId(f.resultId).isPresent());
        assertThat(present)
                .as("rejected insert must leave no row for the result")
                .isFalse();
    }

    // ──────────────────────────────────────────────────────────────────
    // Fixtures
    // ──────────────────────────────────────────────────────────────────

    private record Fixture(UUID deviceId, UUID commandId, UUID resultId) {
    }

    private Fixture newDeviceCommandResult() {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(TENANT);
        device.setHostname("PG-ATOMIC-" + UUID.randomUUID());
        device.setMachineFingerprint("fp-" + UUID.randomUUID());
        device.setOsType(OsType.WINDOWS);
        device.setStatus(DeviceStatus.ONLINE);
        device = deviceRepository.saveAndFlush(device);

        EndpointCommand cmd = new EndpointCommand();
        cmd.setTenantId(TENANT);
        cmd.setDevice(device);
        cmd.setCommandType(CommandType.COLLECT_INVENTORY);
        cmd.setIdempotencyKey("inv-" + UUID.randomUUID());
        cmd.setPayload(Map.of());
        cmd.setStatus(CommandStatus.SUCCEEDED);
        cmd.setIssuedBySubject("admin@example.com");
        cmd.setIssuedAt(Instant.now());
        cmd = commandRepository.saveAndFlush(cmd);

        EndpointCommandResult res = new EndpointCommandResult();
        res.setTenantId(TENANT);
        res.setCommand(cmd);
        res.setDevice(device);
        res.setResultStatus(CommandResultStatus.SUCCEEDED);
        res.setResultPayload(Map.of());
        res.setReportedAt(Instant.now());
        res = resultRepository.saveAndFlush(res);

        return new Fixture(device.getId(), cmd.getId(), res.getId());
    }

    private EndpointDevice reload(UUID deviceId) {
        return deviceRepository.findById(deviceId).orElseThrow();
    }

    private EndpointCommand command(UUID commandId) {
        return commandRepository.findById(commandId).orElseThrow();
    }

    private EndpointCommandResult result(UUID resultId) {
        return resultRepository.findById(resultId).orElseThrow();
    }

    private EndpointSoftwareInventoryStateHistory history(
            UUID sourceResultId, int schemaVersion, int appCount,
            String hash, List<Map<String, Object>> digest) {
        EndpointSoftwareInventoryStateHistory h =
                new EndpointSoftwareInventoryStateHistory();
        h.setTenantId(TENANT);
        // deviceId is taken from the owning device of the result; reuse the
        // result's device for the composite FK.
        h.setDeviceId(result(sourceResultId).getDevice().getId());
        h.setSourceCommandResultId(sourceResultId);
        h.setSchemaVersion(schemaVersion);
        h.setAppCount(appCount);
        h.setAppsDigestHash(hash);
        h.setAppsDigest(new ArrayList<>(digest));
        h.setCapturedAt(Instant.now());
        return h;
    }

    private EndpointSoftwareInventorySnapshot validSnapshot(EndpointDevice device) {
        EndpointSoftwareInventorySnapshot s =
                new EndpointSoftwareInventorySnapshot();
        s.setTenantId(TENANT);
        s.setDevice(device);
        s.setSchemaVersion(1);
        s.setSupported(true);
        s.setTruncated(false);
        // apps_available=false keeps the V8 apps_available_pair CHECK happy
        // without an apps_collected_at.
        s.setAppsAvailable(false);
        s.setSummaryCollectedAt(Instant.now());
        return s;
    }

    private Map<String, Object> appMap(String name, String version,
                                       String publisher, String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("displayName", name);
        m.put("displayVersion", version);
        m.put("publisher", publisher);
        m.put("installSource", source);
        m.put("uninstallStringPresent", true);
        return m;
    }

    private Map<String, Object> summaryWithApps(List<Map<String, Object>> apps) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("schemaVersion", 1);
        inventory.put("supported", true);
        inventory.put("appCount", apps.size());
        inventory.put("wingetReady", true);
        inventory.put("apps", apps);
        details.put("inventory", inventory);
        return details;
    }
}
