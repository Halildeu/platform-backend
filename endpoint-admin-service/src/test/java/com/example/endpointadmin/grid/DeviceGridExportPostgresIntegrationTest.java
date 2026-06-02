package com.example.endpointadmin.grid;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.commonexport.CsvStreamingExporter;
import com.example.commonexport.ExportColumn;
import com.example.commonexport.ExportQuery;
import com.example.endpointadmin.grid.DeviceGridColumns.GridColumn;
import com.example.endpointadmin.grid.DeviceGridQueryBuilder.ExportMode;
import com.example.endpointadmin.grid.DeviceGridQueryBuilder.GridSql;
import com.example.endpointadmin.model.EndpointDeviceHealthSnapshot;
import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;
import com.example.endpointadmin.repository.EndpointDeviceHealthSnapshotRepository;
import com.example.endpointadmin.repository.EndpointOutdatedSoftwareSnapshotRepository;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE — #1154 PR-2b export integration: the export query MUST resolve against
 * the live non-{@code public} schema ({@code endpoint_admin_service}) and the
 * shared {@code common-export} CSV exporter MUST emit the v2 columns with
 * fail-closed NULL cells for devices that have no snapshot.
 *
 * <p>Reuses the PR-2a guard topology (no {@code currentSchema} on the URL →
 * search_path stays public). An unqualified export query would 500 here.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DeviceGridExportPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";

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
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @Autowired
    private EndpointDeviceHealthSnapshotRepository healthRepo;
    @Autowired
    private EndpointOutdatedSoftwareSnapshotRepository outdatedRepo;
    @Autowired
    private JdbcTemplate jdbc;

    private DeviceGridQueryBuilder builder() {
        return new DeviceGridQueryBuilder(SCHEMA, 200, 200, 200);
    }

    @Test
    void rawCsvExportResolvesNonPublicSchema_v2Columns_failClosedNulls() {
        UUID tenant = UUID.randomUUID();
        UUID withSnaps = UUID.randomUUID();
        UUID noSnaps = UUID.randomUUID();
        insertDevice(withSnaps, tenant, "host-with-snaps");
        insertDevice(noSnaps, tenant, "host-no-snaps");

        Instant t = Instant.parse("2026-05-30T10:00:00Z");
        saveHealth(tenant, withSnaps, t.minusSeconds(3600), 10);
        saveHealth(tenant, withSnaps, t, 90);
        saveOutdated(tenant, withSnaps, t.minusSeconds(3600), 2);
        saveOutdated(tenant, withSnaps, t, 7);

        DeviceGridQueryBuilder b = builder();
        List<GridColumn> cols = b.resolveExportColumns(ExportMode.RAW, null);
        GridSql q = b.buildExportQuery(tenant, ExportMode.RAW,
                new DeviceGridExportRequest("csv", "raw", null, null, null, null), cols);
        List<ExportColumn> exportColumns = cols.stream()
                .map(c -> new ExportColumn(c.colId(), c.header()))
                .toList();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // If any table were unqualified, this jdbc.query throws "relation ...
        // does not exist" (the live 500).
        CsvStreamingExporter.exportWithColumns(
                new NamedParameterJdbcTemplate(jdbc.getDataSource()),
                new ExportQuery(q.sql(), q.params()), exportColumns, out);

        byte[] bytes = out.toByteArray();
        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8); // skip BOM
        String[] lines = csv.split("\\r?\\n");

        // Header row carries the server-resolved Turkish labels.
        assertThat(lines[0]).contains("Bilgisayar Adı").contains("Bellek %")
                .contains("Güncellenebilir Yazılım");

        int hostIdx = DeviceGridColumns.allColumnIds().indexOf("hostname");
        int memIdx = DeviceGridColumns.allColumnIds().indexOf("health_memory_used_percent");
        int upgIdx = DeviceGridColumns.allColumnIds().indexOf("outdated_upgrade_count");

        String[] withRow = dataRow(lines, hostIdx, "host-with-snaps");
        String[] noRow = dataRow(lines, hostIdx, "host-no-snaps");

        // Latest snapshot values present for the device that has them.
        assertThat(withRow[memIdx]).isEqualTo("90");
        assertThat(withRow[upgIdx]).isEqualTo("7");

        // Fail-closed: no snapshot ⇒ EMPTY cells (authoritative "no snapshot"),
        // never a misleading 0 or stale value.
        assertThat(noRow[memIdx]).isEmpty();
        assertThat(noRow[upgIdx]).isEmpty();
    }

    /**
     * WEB-015 v2-a (Codex 019e8785 iter-3 P1 absorb) — new BE-025 +
     * AG-041 LATERAL joins must resolve under the non-public schema
     * AND emit the expected scalar values for a device whose latest
     * compliance evaluation carries 2 prohibitedInstalled items + a
     * persisted app-control ENFORCE/RUNNING snapshot.
     *
     * <p>Also asserts the no-snapshot/no-evaluation device renders
     * NO_EVALUATION + NULL findings + NULL app-control cells (the
     * fail-closed "no data" semantics — never synthesized to UNKNOWN).
     */
    @Test
    void v2aColumnsResolveSchemaQualified_withProhibitedAndAppControl_andFailClosedNulls() {
        UUID tenant = UUID.randomUUID();
        UUID withSnaps = UUID.randomUUID();
        UUID noSnaps = UUID.randomUUID();
        insertDevice(withSnaps, tenant, "v2a-with");
        insertDevice(noSnaps, tenant, "v2a-without");

        Instant t = Instant.parse("2026-06-02T10:00:00Z");
        // Seed BE-025 compliance evaluation with 2 prohibitedInstalled items.
        insertComplianceEvaluation(tenant, withSnaps, t, "UNAUTHORIZED",
                "{\"matchedItems\":{\"prohibitedInstalled\":["
                        + "{\"name\":\"Banned-RAT\"},"
                        + "{\"name\":\"TeamViewer\"}"
                        + "]}}");
        // Seed AG-041 app-control snapshot ENFORCE / RUNNING.
        insertAppControlSnapshot(tenant, withSnaps, t, "ENFORCE", "RUNNING");

        DeviceGridQueryBuilder b = builder();
        List<GridColumn> cols = b.resolveExportColumns(ExportMode.RAW, null);
        GridSql q = b.buildExportQuery(tenant, ExportMode.RAW,
                new DeviceGridExportRequest("csv", "raw", null, null, null, null), cols);
        List<ExportColumn> exportColumns = cols.stream()
                .map(c -> new ExportColumn(c.colId(), c.header()))
                .toList();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // If pe or ac LATERAL subqueries were unqualified, this would 500.
        CsvStreamingExporter.exportWithColumns(
                new NamedParameterJdbcTemplate(jdbc.getDataSource()),
                new ExportQuery(q.sql(), q.params()), exportColumns, out);

        byte[] bytes = out.toByteArray();
        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        String[] lines = csv.split("\\r?\\n");

        // Header row carries the new server-resolved Turkish labels.
        assertThat(lines[0])
                .contains("Yasaklı Yazılım Durumu")
                .contains("Uygunluk Kararı")
                .contains("Yasaklı Yazılım Bulgu Sayısı")
                .contains("WDAC Modu")
                .contains("AppIDSvc Durumu");

        int hostIdx = DeviceGridColumns.allColumnIds().indexOf("hostname");
        int prohibStatusIdx =
                DeviceGridColumns.allColumnIds().indexOf("prohibited_status");
        int prohibDecIdx =
                DeviceGridColumns.allColumnIds().indexOf("prohibited_decision");
        int prohibCountIdx =
                DeviceGridColumns.allColumnIds().indexOf("prohibited_findings_count");
        int wdacIdx =
                DeviceGridColumns.allColumnIds().indexOf("app_control_wdac_mode");
        int svcIdx =
                DeviceGridColumns.allColumnIds().indexOf("app_control_app_id_svc_state");

        String[] withRow = dataRow(lines, hostIdx, "v2a-with");
        String[] noRow = dataRow(lines, hostIdx, "v2a-without");

        // Device with evaluation + app-control snapshot.
        assertThat(withRow[prohibStatusIdx]).isEqualTo("OK");
        assertThat(withRow[prohibDecIdx]).isEqualTo("UNAUTHORIZED");
        assertThat(withRow[prohibCountIdx]).isEqualTo("2");
        assertThat(withRow[wdacIdx]).isEqualTo("ENFORCE");
        assertThat(withRow[svcIdx]).isEqualTo("RUNNING");

        // No-evaluation / no-snapshot device: NO_EVALUATION + NULL cells.
        // The CASE expression returns the literal NO_EVALUATION; the other
        // four columns are NULL via LEFT JOIN (NOT synthesized to UNKNOWN).
        assertThat(noRow[prohibStatusIdx]).isEqualTo("NO_EVALUATION");
        assertThat(noRow[prohibDecIdx]).isEmpty();
        assertThat(noRow[prohibCountIdx]).isEmpty();
        assertThat(noRow[wdacIdx]).isEmpty();
        assertThat(noRow[svcIdx]).isEmpty();
    }

    @Test
    void preflightCountsTenantRows() {
        UUID tenant = UUID.randomUUID();
        insertDevice(UUID.randomUUID(), tenant, "p1");
        insertDevice(UUID.randomUUID(), tenant, "p2");
        insertDevice(UUID.randomUUID(), tenant, "p3");

        GridSql count = builder().buildCountPreflight(tenant, ExportMode.RAW,
                new DeviceGridExportRequest("csv", "raw", null, null, null, null), 50000);
        Long matched = new NamedParameterJdbcTemplate(jdbc.getDataSource())
                .queryForObject(count.sql(), count.params(), Long.class);
        assertThat(matched).isEqualTo(3L);
    }

    private String[] dataRow(String[] lines, int hostIdx, String hostname) {
        for (int i = 1; i < lines.length; i++) {
            String[] cells = lines[i].split(";", -1);
            if (cells.length > hostIdx && hostname.equals(cells[hostIdx])) {
                return cells;
            }
        }
        throw new AssertionError("row not found for hostname " + hostname);
    }

    // ───────────────────────── seed helpers ─────────────────────────

    private void insertDevice(UUID id, UUID tenant, String hostname) {
        Timestamp now = Timestamp.from(Instant.parse("2026-05-30T09:00:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, hostname, os_type, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, tenant, hostname, now, now);
    }

    private void saveHealth(UUID tenant, UUID device, Instant collectedAt, int memoryUsedPercent) {
        EndpointDeviceHealthSnapshot s = new EndpointDeviceHealthSnapshot();
        s.setTenantId(tenant);
        s.setDeviceId(device);
        s.setSchemaVersion((short) 1);
        s.setSupported(true);
        s.setProbeComplete(true);
        s.setAnyLowDisk(false);
        s.setFixedDiskCount(1);
        s.setFixedDisksTruncated(false);
        s.setMaxFixedDisks(64);
        s.setMemoryUsedPercent((short) memoryUsedPercent);
        s.setMemoryHighPressure(false);
        s.setUptimeDays(10);
        s.setLongUptimeWarning(false);
        s.setSourceUsed("win32");
        s.setPayloadHashSha256("a".repeat(64));
        s.setCollectedAt(collectedAt);
        healthRepo.saveAndFlush(s);
    }

    private void saveOutdated(UUID tenant, UUID device, Instant collectedAt, int upgradeCount) {
        EndpointOutdatedSoftwareSnapshot s = new EndpointOutdatedSoftwareSnapshot();
        s.setTenantId(tenant);
        s.setDeviceId(device);
        s.setSchemaVersion((short) 1);
        s.setSupported(true);
        s.setProbeComplete(true);
        s.setUpgradeCount(upgradeCount);
        s.setUpgradeTruncated(false);
        s.setMaxUpgrade(100);
        s.setSourceUsed("winget");
        s.setPayloadHashSha256("b".repeat(64));
        s.setCollectedAt(collectedAt);
        outdatedRepo.saveAndFlush(s);
    }

    /**
     * Seed an endpoint_compliance_evaluations row with the given decision and
     * raw JSONB evidence (the BE-025 read path that DeviceGridColumns
     * prohibited_* columns walk via the pe LATERAL alias).
     */
    private void insertComplianceEvaluation(
            UUID tenant, UUID device, Instant evaluatedAt, String decision, String evidenceJson) {
        Timestamp ts = Timestamp.from(evaluatedAt);
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_compliance_evaluations "
                        + "(id, tenant_id, device_id, evaluated_at, decision, "
                        + " reasons, blocking_reasons, warnings, evidence, "
                        + " catalog_policy_hash, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, "
                        + "        '[]'::jsonb, '[]'::jsonb, '[]'::jsonb, ?::jsonb, "
                        + "        ?, ?)",
                UUID.randomUUID(), tenant, device, ts, decision,
                evidenceJson, "a".repeat(64), ts);
    }

    /**
     * Seed an endpoint_app_control_snapshots row with the given WDAC mode +
     * AppIDSvc state (the AG-041 read path that DeviceGridColumns
     * app_control_* columns read via the ac LATERAL alias).
     */
    private void insertAppControlSnapshot(
            UUID tenant, UUID device, Instant collectedAt,
            String wdacMode, String appIdSvcState) {
        Timestamp ts = Timestamp.from(collectedAt);
        // V26 endpoint_app_control_snapshots is fully NOT NULL on most
        // scalars; seed sensible defaults for the columns we don't probe
        // (booleans = false, enums = NOT_CONFIGURED/UNKNOWN, counters = 0).
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_app_control_snapshots "
                        + "(id, tenant_id, device_id, schema_version, supported, "
                        + " probe_complete, wdac_queryable, app_locker_queryable, "
                        + " wdac_mode, "
                        + " app_locker_exe_rule, app_locker_dll_rule, "
                        + " app_locker_script_rule, app_locker_msi_rule, "
                        + " app_locker_appx_rule, "
                        + " app_locker_app_id_svc_state, app_locker_app_id_svc_startup, "
                        + " probe_duration_ms, payload_hash_sha256, collected_at) "
                        + "VALUES (?, ?, ?, 1, true, true, true, true, "
                        + "        ?, "
                        + "        'NOT_CONFIGURED', 'NOT_CONFIGURED', "
                        + "        'NOT_CONFIGURED', 'NOT_CONFIGURED', "
                        + "        'NOT_CONFIGURED', "
                        + "        ?, 'MANUAL', "
                        + "        100, ?, ?)",
                UUID.randomUUID(), tenant, device,
                wdacMode, appIdSvcState, "c".repeat(64), ts);
    }
}
