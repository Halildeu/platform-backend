package com.example.endpointadmin.service;

import com.example.endpointadmin.audit.NoOpAuditChainLock;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareInventoryDiffEntryResponse;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareInventoryDiffResponse;
import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointSoftwareInventoryStateHistory;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.model.SoftwareInventoryChangeType;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointSoftwareInventoryStateHistoryRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-024 — diff/history service tests (Faz 22.5).
 *
 * <p>Two layers:
 * <ul>
 *   <li>End-to-end through the real {@link EndpointSoftwareInventoryService}
 *       ingest hook so the append-only history rows are written exactly as
 *       production writes them, then diffed — covers added / removed /
 *       version-changed / no-prior / no-history / no-change + tenant
 *       isolation + idempotency.</li>
 *   <li>Direct {@code computeDiff} unit calls for the pure diff branches
 *       that do not need a persisted ordering.</li>
 * </ul>
 *
 * <p>A mutable {@link MutableClock} guarantees each ingest gets a strictly
 * increasing {@code capturedAt} so the latest-vs-previous ordering is
 * deterministic regardless of wall-clock resolution.
 */
@IsolatedH2DataJpaTest
@Import({
        EndpointSoftwareInventoryDiffServiceTest.FixedClockConfig.class,
        EndpointSoftwareInventoryService.class,
        EndpointSoftwareInventoryDiffService.class,
        EndpointAuditService.class,
        NoOpAuditChainLock.class,
        com.example.endpointadmin.security.SoftwareInventoryPayloadPolicy.class,
        com.example.endpointadmin.security.WinGetEgressPayloadPolicy.class
})
class EndpointSoftwareInventoryDiffServiceTest {

    private static final UUID TENANT_A =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return new MutableClock(Instant.parse("2026-05-28T10:00:00Z"));
        }
    }

    @Autowired
    private EndpointSoftwareInventoryService ingestService;

    @Autowired
    private EndpointSoftwareInventoryDiffService diffService;

    @Autowired
    private EndpointSoftwareInventoryStateHistoryRepository historyRepository;

    @Autowired
    private EndpointDeviceRepository deviceRepository;

    @Autowired
    private EndpointCommandRepository commandRepository;

    @Autowired
    private EndpointCommandResultRepository resultRepository;

    @Autowired
    private Clock clock;

    @Autowired
    private TestEntityManager entityManager;

    private MutableClock mutableClock() {
        return (MutableClock) clock;
    }

    @BeforeEach
    void resetClock() {
        mutableClock().set(Instant.parse("2026-05-28T10:00:00Z"));
    }

    // ────────────────────────────────────────────────────────────────
    // End-to-end: ingest writes history, diff reads it

    @Test
    void diffComputesAddedRemovedAndVersionChanged() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");

        // First full collect: 7-Zip 24.07 + OldApp 1.0
        ingestFull(device, List.of(
                appMap("7-Zip", "24.07", "Igor Pavlov"),
                appMap("OldApp", "1.0", "OldVendor")));
        mutableClock().advanceSeconds(60);

        // Second full collect: 7-Zip 24.08 (changed) + Firefox 120 (added),
        // OldApp gone (removed).
        ingestFull(device, List.of(
                appMap("7-Zip", "24.08", "Igor Pavlov"),
                appMap("Firefox", "120.0", "Mozilla")));
        flushAndClear();

        AdminSoftwareInventoryDiffResponse diff =
                diffService.diffLatest(ctx(TENANT_A), device.getId());

        assertThat(diff.status())
                .isEqualTo(AdminSoftwareInventoryDiffResponse.DiffStatus.OK);
        assertThat(diff.added()).hasSize(1);
        assertThat(diff.added().get(0).displayName()).isEqualTo("Firefox");
        assertThat(diff.added().get(0).toVersion()).isEqualTo("120.0");
        assertThat(diff.added().get(0).fromVersion()).isNull();
        assertThat(diff.added().get(0).changeType())
                .isEqualTo(SoftwareInventoryChangeType.ADDED);

        assertThat(diff.removed()).hasSize(1);
        assertThat(diff.removed().get(0).displayName()).isEqualTo("OldApp");
        assertThat(diff.removed().get(0).fromVersion()).isEqualTo("1.0");
        assertThat(diff.removed().get(0).toVersion()).isNull();

        assertThat(diff.versionChanged()).hasSize(1);
        assertThat(diff.versionChanged().get(0).displayName()).isEqualTo("7-Zip");
        assertThat(diff.versionChanged().get(0).fromVersion()).isEqualTo("24.07");
        assertThat(diff.versionChanged().get(0).toVersion()).isEqualTo("24.08");

        assertThat(diff.fromAppCount()).isEqualTo(2);
        assertThat(diff.toAppCount()).isEqualTo(2);
    }

    @Test
    void diffWithOnlyOneCaptureReturnsInsufficientHistory() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        ingestFull(device, List.of(appMap("7-Zip", "24.07", "Igor Pavlov")));
        flushAndClear();

        AdminSoftwareInventoryDiffResponse diff =
                diffService.diffLatest(ctx(TENANT_A), device.getId());

        assertThat(diff.status()).isEqualTo(
                AdminSoftwareInventoryDiffResponse.DiffStatus.INSUFFICIENT_HISTORY);
        assertThat(diff.added()).isEmpty();
        assertThat(diff.removed()).isEmpty();
        assertThat(diff.versionChanged()).isEmpty();
        assertThat(diff.toAppCount()).isEqualTo(1);
    }

    @Test
    void diffWithNoCaptureReturnsNoHistory() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        // No ingest at all.
        AdminSoftwareInventoryDiffResponse diff =
                diffService.diffLatest(ctx(TENANT_A), device.getId());

        assertThat(diff.status()).isEqualTo(
                AdminSoftwareInventoryDiffResponse.DiffStatus.NO_HISTORY);
        assertThat(diff.added()).isEmpty();
    }

    @Test
    void diffWithSummaryOnlyIngestReturnsNoHistory() {
        // Summary-only ingest must NOT append a history row → still NO_HISTORY.
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand cmd = persistCommand(device);
        EndpointCommandResult result = persistResult(cmd);
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("schemaVersion", 1);
        inventory.put("supported", true);
        inventory.put("appCount", 5); // summary only, NO apps[]
        details.put("inventory", inventory);
        ingestService.ingest(device, cmd, result, details);
        flushAndClear();

        assertThat(historyRepository
                .findVisibleToOrgAndDeviceIdOrderByCapturedAtDescCreatedAtDescIdDesc(
                        TENANT_A, device.getId(),
                        org.springframework.data.domain.PageRequest.of(0, 10)))
                .isEmpty();
        assertThat(diffService.diffLatest(ctx(TENANT_A), device.getId()).status())
                .isEqualTo(AdminSoftwareInventoryDiffResponse.DiffStatus.NO_HISTORY);
    }

    @Test
    void diffWithIdenticalConsecutiveCapturesReturnsNoChange() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        ingestFull(device, List.of(appMap("7-Zip", "24.07", "Igor Pavlov")));
        mutableClock().advanceSeconds(60);
        ingestFull(device, List.of(appMap("7-Zip", "24.07", "Igor Pavlov")));
        flushAndClear();

        AdminSoftwareInventoryDiffResponse diff =
                diffService.diffLatest(ctx(TENANT_A), device.getId());

        assertThat(diff.status()).isEqualTo(
                AdminSoftwareInventoryDiffResponse.DiffStatus.NO_CHANGE);
        assertThat(diff.added()).isEmpty();
        assertThat(diff.removed()).isEmpty();
        assertThat(diff.versionChanged()).isEmpty();
    }

    @Test
    void diffIsTenantIsolated() {
        EndpointDevice deviceA = persistDevice(TENANT_A, "PC-A");
        ingestFull(deviceA, List.of(appMap("7-Zip", "24.07", "Igor Pavlov")));
        mutableClock().advanceSeconds(60);
        ingestFull(deviceA, List.of(appMap("7-Zip", "24.08", "Igor Pavlov")));
        flushAndClear();

        // Tenant B asking for tenant A's device id sees NO history (no leak).
        AdminSoftwareInventoryDiffResponse crossTenant =
                diffService.diffLatest(ctx(TENANT_B), deviceA.getId());
        assertThat(crossTenant.status()).isEqualTo(
                AdminSoftwareInventoryDiffResponse.DiffStatus.NO_HISTORY);

        // Tenant A sees the real diff.
        AdminSoftwareInventoryDiffResponse own =
                diffService.diffLatest(ctx(TENANT_A), deviceA.getId());
        assertThat(own.status()).isEqualTo(
                AdminSoftwareInventoryDiffResponse.DiffStatus.OK);
        assertThat(own.versionChanged()).hasSize(1);
    }

    @Test
    void reIngestingSameCommandResultDoesNotAppendDuplicateHistory() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand cmd = persistCommand(device);
        EndpointCommandResult result = persistResult(cmd);
        Map<String, Object> details = summaryWithApps(List.of(
                appMap("7-Zip", "24.07", "Igor Pavlov")));

        ingestService.ingest(device, cmd, result, details);
        flushAndClear();
        EndpointDevice reloaded = deviceRepository.findById(device.getId()).orElseThrow();
        EndpointCommandResult reloadedResult =
                resultRepository.findById(result.getId()).orElseThrow();
        // Re-deliver the SAME command-result (idempotency).
        ingestService.ingest(reloaded, cmd, reloadedResult, details);
        flushAndClear();

        long captures = historyRepository
                .findVisibleToOrgAndDeviceIdOrderByCapturedAtDescCreatedAtDescIdDesc(
                        TENANT_A, device.getId(),
                        org.springframework.data.domain.PageRequest.of(0, 10))
                .size();
        assertThat(captures).as("idempotent: one capture per command-result")
                .isEqualTo(1);
    }

    @Test
    void historyEndpointReturnsCapturesNewestFirst() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        ingestFull(device, List.of(appMap("7-Zip", "24.07", "Igor Pavlov")));
        mutableClock().advanceSeconds(60);
        ingestFull(device, List.of(appMap("7-Zip", "24.08", "Igor Pavlov")));
        flushAndClear();

        var page = diffService.history(ctx(TENANT_A), device.getId(), 0, 20);
        assertThat(page.getTotalElements()).isEqualTo(2);
        // Newest capture first.
        assertThat(page.getContent().get(0).getCapturedAt())
                .isAfter(page.getContent().get(1).getCapturedAt());
    }

    // ────────────────────────────────────────────────────────────────
    // Pure computeDiff branch (no persistence ordering needed)

    @Test
    void computeDiffEmptyToPopulatedIsAllAdded() {
        UUID deviceId = UUID.randomUUID();
        EndpointSoftwareInventoryStateHistory prev = historyRow(deviceId, List.of());
        EndpointSoftwareInventoryStateHistory latest = historyRow(deviceId, List.of(
                digestEntry("7-Zip", "Igor Pavlov", "24.07")));

        AdminSoftwareInventoryDiffResponse diff =
                diffService.computeDiff(deviceId, prev, latest);

        assertThat(diff.status())
                .isEqualTo(AdminSoftwareInventoryDiffResponse.DiffStatus.OK);
        assertThat(diff.added()).hasSize(1);
        assertThat(diff.removed()).isEmpty();
        assertThat(diff.versionChanged()).isEmpty();
        AdminSoftwareInventoryDiffEntryResponse e = diff.added().get(0);
        assertThat(e.changeType()).isEqualTo(SoftwareInventoryChangeType.ADDED);
        assertThat(e.toVersion()).isEqualTo("24.07");
    }

    // ────────────────────────────────────────────────────────────────
    // Helpers

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private AdminTenantContext ctx(UUID tenantId) {
        return new AdminTenantContext(tenantId, "admin@example.com");
    }

    private void ingestFull(EndpointDevice device, List<Map<String, Object>> apps) {
        EndpointDevice reloaded = deviceRepository.findById(device.getId())
                .orElse(device);
        EndpointCommand cmd = persistCommand(reloaded);
        EndpointCommandResult result = persistResult(cmd);
        ingestService.ingest(reloaded, cmd, result, summaryWithApps(apps));
    }

    private EndpointSoftwareInventoryStateHistory historyRow(
            UUID deviceId, List<Map<String, Object>> digest) {
        EndpointSoftwareInventoryStateHistory h =
                new EndpointSoftwareInventoryStateHistory();
        h.setTenantId(TENANT_A);
        // Faz 21.1 PR2b-iv.c — canonical write fixture for the history row
        // too; matches the production ingest path's PR2b-ii write.
        h.setOrgId(TENANT_A);
        h.setDeviceId(deviceId);
        h.setSchemaVersion(1);
        h.setAppsDigest(digest);
        h.setAppCount(digest.size());
        h.setAppsDigestHash(SoftwareInventoryDigest.digestHash(digest));
        h.setCapturedAt(Instant.now(clock));
        return h;
    }

    private Map<String, Object> digestEntry(
            String name, String publisher, String version) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put(SoftwareInventoryDigest.KEY_APP_KEY,
                SoftwareInventoryDigest.appKey(name, publisher, null));
        e.put(SoftwareInventoryDigest.KEY_DISPLAY_NAME, name);
        e.put(SoftwareInventoryDigest.KEY_PUBLISHER, publisher);
        e.put(SoftwareInventoryDigest.KEY_VERSION, version);
        e.put(SoftwareInventoryDigest.KEY_MSI_HASH, null);
        return e;
    }

    private Map<String, Object> appMap(String name, String version, String publisher) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("displayName", name);
        m.put("displayVersion", version);
        m.put("publisher", publisher);
        m.put("installSource", "HKLM");
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

    private EndpointDevice persistDevice(UUID tenantId, String hostname) {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(tenantId);
        // Faz 21.1 PR2b-iv.c — canonical write fixture (PR2b-ii Option A
        // inline); H2 has no V29 trigger so without an explicit set the
        // row would land with org_id NULL and only the OR-fallback branch
        // would be exercised. Setting both columns equally lets the
        // canonical org_id branch run through the H2 suite as well.
        device.setOrgId(tenantId);
        device.setHostname(hostname + "-" + UUID.randomUUID());
        device.setMachineFingerprint("fp-" + UUID.randomUUID());
        device.setOsType(OsType.WINDOWS);
        device.setStatus(DeviceStatus.ONLINE);
        return deviceRepository.saveAndFlush(device);
    }

    private EndpointCommand persistCommand(EndpointDevice device) {
        EndpointCommand cmd = new EndpointCommand();
        cmd.setTenantId(device.getTenantId());
        cmd.setDevice(device);
        cmd.setCommandType(CommandType.COLLECT_INVENTORY);
        cmd.setIdempotencyKey("inv-" + UUID.randomUUID());
        cmd.setPayload(Map.of());
        cmd.setStatus(CommandStatus.SUCCEEDED);
        cmd.setIssuedBySubject("admin@example.com");
        cmd.setIssuedAt(Instant.now(clock));
        return commandRepository.saveAndFlush(cmd);
    }

    private EndpointCommandResult persistResult(EndpointCommand command) {
        EndpointCommandResult result = new EndpointCommandResult();
        result.setTenantId(command.getTenantId());
        result.setCommand(command);
        result.setDevice(command.getDevice());
        result.setResultStatus(CommandResultStatus.SUCCEEDED);
        result.setResultPayload(Map.of());
        result.setReportedAt(Instant.now(clock));
        return resultRepository.saveAndFlush(result);
    }

    /** Test clock that can be advanced to produce monotonically increasing
     *  capturedAt timestamps. */
    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant initial) {
            this.instant = initial;
        }

        void set(Instant next) {
            this.instant = next;
        }

        void advanceSeconds(long seconds) {
            this.instant = this.instant.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
