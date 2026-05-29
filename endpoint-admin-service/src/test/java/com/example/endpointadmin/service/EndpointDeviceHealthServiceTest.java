package com.example.endpointadmin.service;

import com.example.endpointadmin.event.DeviceHealthSnapshotPersistedEvent;
import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointDeviceHealthSnapshot;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointDeviceHealthSnapshotRepository;
import com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE — device-health ingest + query tests (Faz 22.5, AG-033). Mirrors the
 * BE-022 {@code EndpointHardwareInventoryServiceTest} H2 slice.
 *
 * <p>Slice runs on H2; PG-specific composite-FK rejection, DB CHECK
 * coverage, and the {@code lower(bytea)}-free dedupe query proof live in
 * {@code EndpointDeviceHealthPostgresIntegrationTest}.
 *
 * <ul>
 *   <li>First ingest persists snapshot + cascades child disk rows;
 *       collected_at derives from the command-result reportedAt.</li>
 *   <li>Idempotency probe: re-delivering the same
 *       {@code source_command_result_id} returns the existing row.</li>
 *   <li>Payload-hash dedupe (BE-022Q lesson): byte-identical
 *       re-collection under a DIFFERENT command-result no-ops.</li>
 *   <li>Changed payload under a new command-result still appends
 *       (append-only invariant).</li>
 *   <li>Unsupported golden example (probeComplete=false, sourceUsed=none)
 *       persists fail-closed (not treated as a failed ingest).</li>
 *   <li>Event carries bounded metadata only.</li>
 *   <li>{@code findLatest} ordering deterministic.</li>
 *   <li>{@code hasDeviceHealthBlock} predicate gates the hook.</li>
 * </ul>
 */
@IsolatedH2DataJpaTest
@Import({
        EndpointDeviceHealthService.class,
        EndpointDeviceHealthServiceTest.RecordingEventPublisherConfig.class
})
class EndpointDeviceHealthServiceTest {

    private static final UUID TENANT_A =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private EndpointDeviceHealthService service;

    @Autowired
    private EndpointDeviceHealthSnapshotRepository snapshotRepository;

    @Autowired
    private EndpointDeviceRepository deviceRepository;

    @Autowired
    private EndpointCommandRepository commandRepository;

    @Autowired
    private EndpointCommandResultRepository resultRepository;

    @Autowired
    private RecordingEventPublisher recordingEvents;

    // ------------------------------------------------------------------
    // hasDeviceHealthBlock predicate
    // ------------------------------------------------------------------

    @Test
    void hasDeviceHealthBlockTrueForInventoryDeviceHealthSubtree() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", Map.of("deviceHealth", Map.of("schemaVersion", 1)));
        assertThat(EndpointDeviceHealthService.hasDeviceHealthBlock(details)).isTrue();
    }

    @Test
    void hasDeviceHealthBlockTrueForTopLevelAlias() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("deviceHealth", Map.of("schemaVersion", 1));
        assertThat(EndpointDeviceHealthService.hasDeviceHealthBlock(details)).isTrue();
    }

    @Test
    void hasDeviceHealthBlockFalseForHardwareOnlyDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", Map.of("hardware", Map.of("schemaVersion", 1)));
        assertThat(EndpointDeviceHealthService.hasDeviceHealthBlock(details)).isFalse();
    }

    @Test
    void hasDeviceHealthBlockFalseForNull() {
        assertThat(EndpointDeviceHealthService.hasDeviceHealthBlock(null)).isFalse();
    }

    // ------------------------------------------------------------------
    // First ingest happy path (golden healthy example)
    // ------------------------------------------------------------------

    @Test
    void firstIngestPersistsSnapshotWithDisks() {
        recordingEvents.clear();
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        Instant reportedAt = Instant.parse("2026-05-28T12:00:00Z");
        EndpointCommandResult result = persistResult(command, reportedAt);

        Map<String, Object> details = wrap(goldenHealthy());
        EndpointDeviceHealthSnapshot persisted =
                service.ingest(device, command, result, details);

        assertThat(persisted.getId()).isNotNull();
        assertThat(persisted.getDeviceId()).isEqualTo(device.getId());
        assertThat(persisted.getTenantId()).isEqualTo(TENANT_A);
        assertThat(persisted.getSourceCommandResultId()).isEqualTo(result.getId());
        assertThat(persisted.getSchemaVersion()).isEqualTo((short) 1);
        assertThat(persisted.getSupported()).isTrue();
        assertThat(persisted.getProbeComplete()).isTrue();
        assertThat(persisted.getAnyLowDisk()).isFalse();
        assertThat(persisted.getSourceUsed()).isEqualTo("win32");
        assertThat(persisted.getFixedDiskCount()).isEqualTo(1);
        assertThat(persisted.getMaxFixedDisks()).isEqualTo(64);
        assertThat(persisted.getMemoryUsedPercent()).isEqualTo((short) 42);
        assertThat(persisted.getMemoryHighPressure()).isFalse();
        assertThat(persisted.getUptimeDays()).isEqualTo(3);
        assertThat(persisted.getUptimeSeconds()).isEqualTo(259200L);
        assertThat(persisted.getLastBootEpochSec()).isEqualTo(1748275200L);
        assertThat(persisted.getLongUptimeWarning()).isFalse();
        assertThat(persisted.getProbeDurationMs()).isEqualTo(12);
        // collected_at derives from the command-result reportedAt (the
        // wire block carries no timestamp).
        assertThat(persisted.getCollectedAt()).isEqualTo(reportedAt);
        assertThat(persisted.getPayloadHashSha256()).matches("[a-f0-9]{64}");
        assertThat(persisted.getDisks()).hasSize(1);
        assertThat(persisted.getDisks().get(0).getDriveLetter()).isEqualTo("C:");
        assertThat(persisted.getDisks().get(0).getFreePercent()).isEqualTo((short) 50);
        assertThat(persisted.getDisks().get(0).getLowDiskWarning()).isFalse();

        // Bounded event metadata.
        assertThat(recordingEvents.captured()).hasSize(1);
        DeviceHealthSnapshotPersistedEvent event = recordingEvents.captured().get(0);
        assertThat(event.tenantId()).isEqualTo(TENANT_A);
        assertThat(event.deviceId()).isEqualTo(device.getId());
        assertThat(event.snapshotId()).isEqualTo(persisted.getId());
        assertThat(event.sourceCommandId()).isEqualTo(command.getId());
        assertThat(event.diskCount()).isEqualTo(1);
        assertThat(event.supported()).isTrue();
        assertThat(event.probeComplete()).isTrue();
        assertThat(event.anyLowDisk()).isFalse();

        assertThat(snapshotRepository.count()).isEqualTo(1);
    }

    @Test
    void unsupportedGoldenExamplePersistsFailClosed() {
        // supported=false / probeComplete=false / sourceUsed=none with a
        // probeError must persist (NOT be treated as a failed ingest).
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command, Instant.now());

        EndpointDeviceHealthSnapshot persisted =
                service.ingest(device, command, result, wrap(goldenUnsupported()));

        assertThat(persisted.getSupported()).isFalse();
        assertThat(persisted.getProbeComplete()).isFalse();
        assertThat(persisted.getSourceUsed()).isEqualTo("none");
        assertThat(persisted.getFixedDiskCount()).isZero();
        assertThat(persisted.getDisks()).isEmpty();
        assertThat(persisted.getProbeErrors()).hasSize(1);
        assertThat(persisted.getProbeErrors().get(0).get("code"))
                .isEqualTo("UNSUPPORTED_PLATFORM");
    }

    // ------------------------------------------------------------------
    // Idempotency + dedupe
    // ------------------------------------------------------------------

    @Test
    void reIngestWithSameCommandResultReturnsExistingSnapshot() {
        recordingEvents.clear();
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command, Instant.now());

        Map<String, Object> details = wrap(goldenHealthy());
        EndpointDeviceHealthSnapshot first = service.ingest(device, command, result, details);
        recordingEvents.clear();

        EndpointDeviceHealthSnapshot second = service.ingest(device, command, result, details);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(snapshotRepository.count()).isEqualTo(1);
        assertThat(recordingEvents.captured()).isEmpty();
    }

    @Test
    void reIngestIdenticalPayloadUnderDifferentCommandResultDeduplicates() {
        // BE-022Q payload-hash deep-equality dedupe: byte-identical
        // device-health re-collected under a DIFFERENT command-result
        // (so the source_command_result_id probe misses) must return the
        // existing snapshot rather than appending a duplicate row.
        recordingEvents.clear();
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");

        EndpointCommand cmd1 = persistCommand(device);
        EndpointCommandResult result1 = persistResult(cmd1, Instant.now());
        EndpointDeviceHealthSnapshot first =
                service.ingest(device, cmd1, result1, wrap(goldenHealthy()));
        recordingEvents.clear();

        EndpointCommand cmd2 = persistCommand(device);
        EndpointCommandResult result2 = persistResult(cmd2, Instant.now());
        EndpointDeviceHealthSnapshot second =
                service.ingest(device, cmd2, result2, wrap(goldenHealthy()));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(snapshotRepository.count()).isEqualTo(1);
        assertThat(recordingEvents.captured()).isEmpty();
    }

    @Test
    void changedPayloadUnderDifferentCommandResultStillAppends() {
        // Dedupe must NOT swallow a genuine change: a different payload
        // (warning vs healthy → different hash) under a new
        // command-result appends a new snapshot.
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");

        EndpointCommand cmd1 = persistCommand(device);
        EndpointCommandResult result1 = persistResult(cmd1, Instant.now().minusSeconds(3600));
        EndpointDeviceHealthSnapshot first =
                service.ingest(device, cmd1, result1, wrap(goldenHealthy()));

        EndpointCommand cmd2 = persistCommand(device);
        EndpointCommandResult result2 = persistResult(cmd2, Instant.now());
        EndpointDeviceHealthSnapshot second =
                service.ingest(device, cmd2, result2, wrap(goldenWarning()));

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(snapshotRepository.count()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Query — ordering
    // ------------------------------------------------------------------

    @Test
    void findLatestReturnsEmptyForNoSnapshots() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        assertThat(service.findLatest(TENANT_A, device.getId())).isEmpty();
    }

    @Test
    void findLatestReturnsMostRecentCollectedAt() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");

        EndpointCommand cmd1 = persistCommand(device);
        EndpointCommandResult result1 = persistResult(cmd1, Instant.now().minusSeconds(7200));
        EndpointDeviceHealthSnapshot older =
                service.ingest(device, cmd1, result1, wrap(goldenHealthy()));

        EndpointCommand cmd2 = persistCommand(device);
        EndpointCommandResult result2 = persistResult(cmd2, Instant.now().minusSeconds(60));
        // Different payload (warning) so dedupe does not collapse them.
        EndpointDeviceHealthSnapshot newer =
                service.ingest(device, cmd2, result2, wrap(goldenWarning()));

        Optional<EndpointDeviceHealthSnapshot> latest =
                service.findLatest(TENANT_A, device.getId());
        assertThat(latest).isPresent();
        assertThat(latest.get().getId()).isEqualTo(newer.getId());
        assertThat(latest.get().getId()).isNotEqualTo(older.getId());
    }

    // ------------------------------------------------------------------
    // redacted_payload probeErrors substitution
    // ------------------------------------------------------------------

    @Test
    void redactedPayloadSubstitutesBoundedProbeErrors() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command, Instant.now());

        Map<String, Object> dh = goldenUnsupported();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) dh.get("probeErrors");
        // Add extra raw fields the service must NOT propagate to
        // redactedPayload.probeErrors.
        errors.get(0).put("stackTrace", "at internal/inventory/device_health.go:42");
        errors.get(0).put("rawOutput", "stderr blob");

        EndpointDeviceHealthSnapshot persisted =
                service.ingest(device, command, result, wrap(dh));

        // entity scalar bounded.
        assertThat(persisted.getProbeErrors()).hasSize(1);
        Map<String, Object> firstErr = persisted.getProbeErrors().get(0);
        assertThat(firstErr).containsOnlyKeys("code", "source", "summary");

        // redactedPayload.probeErrors also bounded (substitution).
        Object redacted = persisted.getRedactedPayload().get("probeErrors");
        assertThat(redacted).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> redactedList = (List<Map<String, Object>>) redacted;
        assertThat(redactedList).hasSize(1);
        assertThat(redactedList.get(0)).containsOnlyKeys("code", "source", "summary");
        assertThat(redactedList.get(0)).doesNotContainKeys("stackTrace", "rawOutput");
    }

    @Test
    void probeErrorSummaryTruncatedTo256Chars() {
        EndpointDevice device = persistDevice(TENANT_A, "PC-A");
        EndpointCommand command = persistCommand(device);
        EndpointCommandResult result = persistResult(command, Instant.now());

        Map<String, Object> dh = goldenUnsupported();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) dh.get("probeErrors");
        errors.get(0).put("summary", "x".repeat(300));

        EndpointDeviceHealthSnapshot persisted =
                service.ingest(device, command, result, wrap(dh));

        String stored = (String) persisted.getProbeErrors().get(0).get("summary");
        assertThat(stored).hasSize(256);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private EndpointDevice persistDevice(UUID tenantId, String hostname) {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(tenantId);
        device.setHostname(hostname);
        device.setStatus(DeviceStatus.ONLINE);
        device.setOsType(OsType.WINDOWS);
        device.setLastSeenAt(Instant.now());
        return deviceRepository.saveAndFlush(device);
    }

    private EndpointCommand persistCommand(EndpointDevice device) {
        EndpointCommand command = new EndpointCommand();
        command.setTenantId(device.getTenantId());
        command.setDevice(device);
        command.setCommandType(CommandType.COLLECT_INVENTORY);
        command.setStatus(CommandStatus.SUCCEEDED);
        command.setIdempotencyKey("test-cmd-" + UUID.randomUUID());
        command.setIssuedAt(Instant.now());
        command.setIssuedBySubject("test-admin@example.com");
        command.setMaxAttempts(3);
        return commandRepository.saveAndFlush(command);
    }

    private EndpointCommandResult persistResult(EndpointCommand command, Instant reportedAt) {
        EndpointCommandResult result = new EndpointCommandResult();
        result.setTenantId(command.getTenantId());
        result.setCommand(command);
        result.setDevice(command.getDevice());
        result.setResultStatus(CommandResultStatus.SUCCEEDED);
        result.setReportedAt(reportedAt);
        result.setResultPayload(new LinkedHashMap<>());
        return resultRepository.saveAndFlush(result);
    }

    private Map<String, Object> wrap(Map<String, Object> deviceHealth) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("deviceHealth", deviceHealth);
        details.put("inventory", inventory);
        return details;
    }

    private Map<String, Object> goldenHealthy() {
        Map<String, Object> dh = new LinkedHashMap<>();
        dh.put("schemaVersion", 1);
        dh.put("supported", true);
        dh.put("probeComplete", true);
        List<Map<String, Object>> disks = new ArrayList<>();
        disks.add(disk("C:", 536870912000L, 268435456000L, 50, false));
        dh.put("fixedDisks", disks);
        dh.put("fixedDiskCount", 1);
        dh.put("fixedDisksTruncated", false);
        dh.put("maxFixedDisks", 64);
        dh.put("memory", memory(17179869184L, 9663676416L, 42, false, 25769803776L, 10307921920L));
        dh.put("uptime", uptime(1748275200L, 259200L, 3, false));
        dh.put("anyLowDisk", false);
        dh.put("sourceUsed", "win32");
        dh.put("probeDurationMs", 12);
        return dh;
    }

    private Map<String, Object> goldenWarning() {
        Map<String, Object> dh = new LinkedHashMap<>();
        dh.put("schemaVersion", 1);
        dh.put("supported", true);
        dh.put("probeComplete", true);
        List<Map<String, Object>> disks = new ArrayList<>();
        disks.add(disk("C:", 536870912000L, 5368709120L, 1, true));
        dh.put("fixedDisks", disks);
        dh.put("fixedDiskCount", 1);
        dh.put("fixedDisksTruncated", false);
        dh.put("maxFixedDisks", 64);
        dh.put("memory", memory(17179869184L, 1073741824L, 95, true, 34359738368L, 25769803776L));
        dh.put("uptime", uptime(1745683200L, 2851200L, 33, true));
        dh.put("anyLowDisk", true);
        dh.put("sourceUsed", "win32");
        dh.put("probeDurationMs", 18);
        return dh;
    }

    private Map<String, Object> goldenUnsupported() {
        Map<String, Object> dh = new LinkedHashMap<>();
        dh.put("schemaVersion", 1);
        dh.put("supported", false);
        dh.put("probeComplete", false);
        dh.put("fixedDisks", new ArrayList<>());
        dh.put("fixedDiskCount", 0);
        dh.put("fixedDisksTruncated", false);
        dh.put("maxFixedDisks", 64);
        dh.put("memory", memory(0L, 0L, 0, false, 0L, 0L));
        dh.put("uptime", uptime(0L, 0L, 0, false));
        dh.put("anyLowDisk", false);
        dh.put("sourceUsed", "none");
        List<Map<String, Object>> errors = new ArrayList<>();
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("source", "none");
        err.put("code", "UNSUPPORTED_PLATFORM");
        err.put("summary", "device-health probe not supported on this runtime");
        errors.add(err);
        dh.put("probeErrors", errors);
        dh.put("probeDurationMs", 0);
        return dh;
    }

    private static Map<String, Object> disk(String letter, long total, long free,
                                            int freePct, boolean low) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("driveLetter", letter);
        d.put("totalBytes", total);
        d.put("freeBytes", free);
        d.put("freePercent", freePct);
        d.put("lowDiskWarning", low);
        return d;
    }

    private static Map<String, Object> memory(long total, long avail, int usedPct,
                                              boolean pressure, long commitLimit, long commitUsed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalPhysicalBytes", total);
        m.put("availableBytes", avail);
        m.put("usedPercent", usedPct);
        m.put("highPressureWarning", pressure);
        m.put("commitLimitBytes", commitLimit);
        m.put("commitUsedBytes", commitUsed);
        return m;
    }

    private static Map<String, Object> uptime(long lastBoot, long seconds, int days, boolean longUp) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("lastBootEpochSec", lastBoot);
        u.put("uptimeSeconds", seconds);
        u.put("uptimeDays", days);
        u.put("longUptimeWarning", longUp);
        return u;
    }

    // ------------------------------------------------------------------
    // Recording event listener
    // ------------------------------------------------------------------

    @TestConfiguration
    static class RecordingEventPublisherConfig {
        @Bean
        RecordingEventPublisher recordingEventPublisher() {
            return new RecordingEventPublisher();
        }
    }

    static class RecordingEventPublisher {
        private final List<DeviceHealthSnapshotPersistedEvent> events = new ArrayList<>();

        @EventListener
        public void onDeviceHealthSnapshotPersisted(DeviceHealthSnapshotPersistedEvent event) {
            events.add(event);
        }

        List<DeviceHealthSnapshotPersistedEvent> captured() {
            return List.copyOf(events);
        }

        void clear() {
            events.clear();
        }
    }
}
