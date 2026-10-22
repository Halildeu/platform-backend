package com.example.endpointadmin.service;

import com.example.endpointadmin.audit.AuditIntegrityVerifier;
import com.example.endpointadmin.audit.PgAdvisoryAuditChainLock;
import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.dto.v1.admin.AdminDisplayPolicyAssetResponse;
import com.example.endpointadmin.dto.v1.admin.AdminDisplayPolicyResponse;
import com.example.endpointadmin.dto.v1.admin.ApproveEndpointCommandRequest;
import com.example.endpointadmin.dto.v1.admin.ClearDisplayPolicyRequest;
import com.example.endpointadmin.dto.v1.admin.SetDisplayPolicyRequest;
import com.example.endpointadmin.model.ApprovalDecision;
import com.example.endpointadmin.model.DisplayPolicyOperation;
import com.example.endpointadmin.model.EndpointDisplayPolicy;
import com.example.endpointadmin.model.EndpointDisplayPolicyAsset;
import com.example.endpointadmin.repository.EndpointDisplayPolicyRepository;
import com.example.endpointadmin.repository.EndpointDisplayPolicyRevisionRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.AesGcmDeviceSecretProtector;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #508 slice-2b — end-to-end PG integration proof of the Codex 019ea911 RED-fix
 * WIRING: the dispatch service writes only a revision + a PENDING command, and
 * the current desired-state row is promoted ONLY when a second admin APPROVES the
 * command via the existing dual-control surface (which publishes the generic
 * decision event the {@link DisplayPolicyApprovalListener} consumes IN the same
 * transaction). A REJECT leaves the current row unwritten.
 *
 * <p>Mirrors {@link EndpointInstallCommandAuditPostgresIntegrationTest}: real PG
 * 16 + Flyway + {@code ddl-auto=validate} + the genuine audit chain lock, each
 * scenario in its own committed {@link TransactionTemplate} transaction (the
 * class is {@code NOT_SUPPORTED} so the default rollback tx does not wrap the
 * flush, and the synchronous in-tx approval event truly fires + commits).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        TimeConfig.class,
        EndpointAdminCommandService.class,
        EndpointDisplayPolicyService.class,
        DisplayPolicyAssetService.class,
        DisplayPolicyApprovalListener.class,
        EndpointAuditService.class,
        EndpointInstallPreflightService.class,
        EndpointCommandSecretService.class,
        AesGcmDeviceSecretProtector.class,
        PgAdvisoryAuditChainLock.class,
        AuditIntegrityVerifier.class
})
class DisplayPolicyDispatchPostgresIntegrationTest {

    private static final String PROPOSER = "alice@example.com";
    private static final String APPROVER = "bob@example.com";
    private static final String MANAGED_CAPABLE = "{\"capabilities\":[\"SET_DISPLAY_POLICY\",\""
            + EndpointDisplayPolicyService.MANAGED_ASSET_CAPABILITY + "\"]}";

    @MockitoBean
    private EndpointEnrollmentService enrollmentService;

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
        registry.add("endpoint-admin.secrets.encryption-key",
                () -> "test-endpoint-command-secret-key");
        // Enable the dark-shipped display-policy surface for the test.
        registry.add("endpoint-admin.display-policy.enabled", () -> "true");
    }

    @Autowired private EndpointDisplayPolicyService displayPolicyService;
    @Autowired private DisplayPolicyAssetService assetService;
    @Autowired private EndpointAdminCommandService commandService;
    @Autowired private EndpointDisplayPolicyRepository policyRepository;
    @Autowired private EndpointDisplayPolicyRevisionRepository revisionRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    @Test
    void enforceThenApprove_promotesCurrent_andDispatchNeverWritesCurrent() {
        UUID tenant = UUID.randomUUID();
        UUID device = seedDeviceWithFreshCapableHeartbeat(tenant);

        AdminDisplayPolicyResponse proposed = tx().execute(s ->
                displayPolicyService.enforce(ctx(tenant, PROPOSER), device, enforceRequest()));
        UUID commandId = proposed.openProposal().commandId();

        // Dispatch wrote a revision + PENDING command but NOT the current row.
        assertThat(proposed.operation()).isNull();
        assertThat(activePolicyCount(device)).isZero();
        assertThat(revisionRepository.findOpenProposals(tenant, device)).hasSize(1);

        // A DIFFERENT admin approves → the listener promotes the current row.
        tx().executeWithoutResult(s -> commandService.approveCommand(
                ctx(tenant, APPROVER), commandId,
                new ApproveEndpointCommandRequest(ApprovalDecision.APPROVE, null)));

        EndpointDisplayPolicy current = currentRow(tenant, device);
        assertThat(current.getOperation()).isEqualTo(DisplayPolicyOperation.ENFORCE);
        assertThat(current.getClearedAt()).isNull();
        assertThat(current.getScreensaverEnabled()).isTrue();
        assertThat(current.getWallpaperStyle().name()).isEqualTo("FILL");
        assertThat(current.getCreatedBySubject()).isEqualTo(PROPOSER);
        assertThat(current.getLastUpdatedBySubject()).isEqualTo(APPROVER);
    }

    @Test
    void enforceThenReject_leavesCurrentUnwritten() {
        UUID tenant = UUID.randomUUID();
        UUID device = seedDeviceWithFreshCapableHeartbeat(tenant);

        AdminDisplayPolicyResponse proposed = tx().execute(s ->
                displayPolicyService.enforce(ctx(tenant, PROPOSER), device, enforceRequest()));
        UUID commandId = proposed.openProposal().commandId();

        tx().executeWithoutResult(s -> commandService.approveCommand(
                ctx(tenant, APPROVER), commandId,
                new ApproveEndpointCommandRequest(ApprovalDecision.REJECT, "not for kiosk")));

        // RED-fix: a rejected proposal NEVER becomes current truth.
        assertThat(policyRepository.findByTenantIdAndDeviceId(tenant, device)).isEmpty();
    }

    @Test
    void enforceApprove_thenClearApprove_flipsCurrentToCleared() {
        UUID tenant = UUID.randomUUID();
        UUID device = seedDeviceWithFreshCapableHeartbeat(tenant);

        UUID enforceCmd = tx().execute(s ->
                displayPolicyService.enforce(ctx(tenant, PROPOSER), device, enforceRequest()))
                .openProposal().commandId();
        tx().executeWithoutResult(s -> commandService.approveCommand(
                ctx(tenant, APPROVER), enforceCmd,
                new ApproveEndpointCommandRequest(ApprovalDecision.APPROVE, null)));
        assertThat(currentRow(tenant, device).getOperation()).isEqualTo(DisplayPolicyOperation.ENFORCE);

        UUID clearCmd = tx().execute(s ->
                displayPolicyService.clear(ctx(tenant, PROPOSER), device,
                        new ClearDisplayPolicyRequest("kiosk decommission")))
                .openProposal().commandId();
        tx().executeWithoutResult(s -> commandService.approveCommand(
                ctx(tenant, APPROVER), clearCmd,
                new ApproveEndpointCommandRequest(ApprovalDecision.APPROVE, null)));

        EndpointDisplayPolicy current = currentRow(tenant, device);
        assertThat(current.getOperation()).isEqualTo(DisplayPolicyOperation.CLEAR);
        assertThat(current.getClearedAt()).isNotNull();
        assertThat(current.getClearedBySubject()).isEqualTo(APPROVER);
        assertThat(current.getScreensaverEnabled()).isNull();
        // exactly one row per device (flipped in place, not a second row)
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM endpoint_display_policies WHERE device_id = ?",
                Integer.class, device);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void managedWallpaper_isServedOnlyToTheDeviceWhoseApprovedPolicyNamesIt() {
        UUID tenant = UUID.randomUUID();
        UUID device = seedDevice(tenant, MANAGED_CAPABLE);
        UUID otherDevice = seedDevice(tenant, MANAGED_CAPABLE);
        byte[] png = png(4096);

        AdminDisplayPolicyAssetResponse uploaded =
                assetService.upload(ctx(tenant, PROPOSER), png, "image/png");
        assertThat(uploaded.created()).isTrue();
        assertThat(assetService.upload(ctx(tenant, APPROVER), png, "image/png").created()).isFalse();

        UUID commandId = tx().execute(s -> displayPolicyService.enforce(
                ctx(tenant, PROPOSER), device, managedWallpaperRequest(uploaded)))
                .openProposal().commandId();
        // Proposed is not approved: the device cannot fetch the image yet.
        assertNotFound(() -> assetService.loadForDevice(device.toString(), uploaded.assetSha256()));

        tx().executeWithoutResult(s -> commandService.approveCommand(
                ctx(tenant, APPROVER), commandId,
                new ApproveEndpointCommandRequest(ApprovalDecision.APPROVE, null)));

        EndpointDisplayPolicy current = currentRow(tenant, device);
        assertThat(current.getWallpaperAssetRef()).isEqualTo(uploaded.assetRef());
        assertThat(current.getWallpaperUserCannotChange()).isTrue();

        EndpointDisplayPolicyAsset served =
                assetService.loadForDevice(device.toString(), uploaded.assetSha256());
        assertThat(served.getContent()).isEqualTo(png);
        assertThat(served.getContentType()).isEqualTo("image/png");

        // Same tenant, same image, but no approved policy naming it.
        assertNotFound(() -> assetService.loadForDevice(otherDevice.toString(), uploaded.assetSha256()));
    }

    @Test
    void aConcurrentIdenticalUpload_resolvesToTheCommittedRow() throws Exception {
        UUID tenant = UUID.randomUUID();
        byte[] png = png(512);
        String sha = DisplayPolicyAssetService.sha256Hex(png);

        try (Connection winner = dataSource.getConnection()) {
            winner.setAutoCommit(false);
            try (PreparedStatement ps = winner.prepareStatement("INSERT INTO endpoint_display_policy_assets "
                    + "(id, tenant_id, sha256, content_type, size_bytes, content, created_by_subject, created_at) "
                    + "VALUES (?, ?, ?, 'image/png', ?, ?, 'winner', now())")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, tenant);
                ps.setString(3, sha);
                ps.setInt(4, png.length);
                ps.setBytes(5, png);
                ps.executeUpdate();
            }
            // The winner's row is not committed, so the upload's existence check
            // misses it and its insert blocks on the unique index until the commit.
            CompletableFuture<AdminDisplayPolicyAssetResponse> loser = CompletableFuture.supplyAsync(
                    () -> assetService.upload(ctx(tenant, APPROVER), png, "image/png"));
            awaitInsertBlockedOnLock();
            winner.commit();

            AdminDisplayPolicyAssetResponse res = loser.get(30, TimeUnit.SECONDS);
            assertThat(res.created()).isFalse();
            assertThat(res.assetSha256()).isEqualTo(sha);
        }
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM endpoint_display_policy_assets WHERE tenant_id = ?",
                Integer.class, tenant);
        assertThat(rows).isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static AdminTenantContext ctx(UUID tenant, String subject) {
        return new AdminTenantContext(tenant, subject);
    }

    private static SetDisplayPolicyRequest managedWallpaperRequest(AdminDisplayPolicyAssetResponse asset) {
        return new SetDisplayPolicyRequest(
                DisplayPolicyOperation.ENFORCE,
                "corporate wallpaper",
                null,
                new SetDisplayPolicyRequest.Wallpaper(true, "fill", true,
                        asset.assetRef(), asset.assetSha256(), asset.contentType()));
    }

    /** A PNG signature followed by every byte value, so a signed/unsigned slip shows. */
    private static byte[] png(int size) {
        byte[] b = new byte[size];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, b, 0, magic.length);
        for (int i = magic.length; i < size; i++) {
            b[i] = (byte) (i % 256);
        }
        return b;
    }

    private void awaitInsertBlockedOnLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE wait_event_type = 'Lock' "
                    + "AND query ILIKE '%insert into%endpoint_display_policy_assets%'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the upload's insert never waited on the uncommitted row");
    }

    private static void assertNotFound(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private static SetDisplayPolicyRequest enforceRequest() {
        return new SetDisplayPolicyRequest(
                DisplayPolicyOperation.ENFORCE,
                "kiosk lockdown",
                new SetDisplayPolicyRequest.Screensaver(true, 600, true,
                        "c:\\windows\\system32\\scrnsave.scr"),
                new SetDisplayPolicyRequest.Wallpaper(true, "fill", true,
                        "wallpapers/corp.png", null, "image/png"));
    }

    private EndpointDisplayPolicy currentRow(UUID tenant, UUID device) {
        Optional<EndpointDisplayPolicy> current =
                policyRepository.findByTenantIdAndDeviceId(tenant, device);
        assertThat(current).isPresent();
        return current.get();
    }

    private int activePolicyCount(UUID device) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM endpoint_display_policies WHERE device_id = ? AND cleared_at IS NULL",
                Integer.class, device);
        return n == null ? 0 : n;
    }

    private UUID seedDeviceWithFreshCapableHeartbeat(UUID tenant) {
        return seedDevice(tenant, "{\"capabilities\":[\"SET_DISPLAY_POLICY\"]}");
    }

    private UUID seedDevice(UUID tenant, String heartbeatPayload) {
        UUID device = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO endpoint_devices "
                        + "(id, tenant_id, hostname, status, os_type, last_seen_at, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 'ONLINE', 'WINDOWS', ?, ?, ?, 0)",
                device, tenant, "host-" + device.toString().substring(0, 8), now, now, now);
        jdbc.update("INSERT INTO endpoint_heartbeats "
                        + "(id, tenant_id, device_id, received_at, payload) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb)",
                UUID.randomUUID(), tenant, device, now, heartbeatPayload);
        return device;
    }
}
