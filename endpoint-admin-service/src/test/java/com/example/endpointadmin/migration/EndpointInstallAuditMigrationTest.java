package com.example.endpointadmin.migration;

import com.example.endpointadmin.model.CatalogInstallerType;
import com.example.endpointadmin.model.CatalogItemStatus;
import com.example.endpointadmin.model.CatalogProvider;
import com.example.endpointadmin.model.CatalogRiskTier;
import com.example.endpointadmin.model.CatalogSourceTrust;
import com.example.endpointadmin.model.CatalogSourceType;
import com.example.endpointadmin.model.CatalogVersionPolicyType;
import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointInstallAudit;
import com.example.endpointadmin.model.EndpointSoftwareCatalogItem;
import com.example.endpointadmin.model.InstallPostVerification;
import com.example.endpointadmin.model.InstallPreflightDecisionRecorded;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointInstallAuditRepository;
import com.example.endpointadmin.repository.EndpointSoftwareCatalogItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-021 — V12 migration sanity test (Codex 019e6dfb iter-3 P2-2).
 *
 * <p>Verifies that Flyway lifts the schema to V12 cleanly, Hibernate
 * {@code validate} mode is happy against {@link EndpointInstallAudit},
 * and a round-trip persist works including the JSONB columns. The
 * deeper PostgreSQL-only CHECK / composite-FK enforcement is exercised
 * by the {@code *IntegrationTest} suites on Testcontainers PG; this
 * slice runs on the embedded H2 default for {@code @DataJpaTest}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class EndpointInstallAuditMigrationTest {

    private static final UUID TENANT_A =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private EndpointInstallAuditRepository installAuditRepository;

    @Autowired
    private EndpointDeviceRepository deviceRepository;

    @Autowired
    private EndpointSoftwareCatalogItemRepository catalogRepository;

    @Autowired
    private EndpointCommandRepository commandRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void v12ContextStartsAndInstallAuditRepositoryWires() {
        // Reaching this assertion proves Flyway applied V12 cleanly and
        // Hibernate validate-mode accepted the EndpointInstallAudit
        // mapping against the resulting table.
        assertThat(installAuditRepository).isNotNull();
    }

    @Test
    void v12TableAcceptsTerminalInstallRoundTrip() {
        EndpointDevice device = deviceRepository.saveAndFlush(testDevice());
        EndpointSoftwareCatalogItem catalog = catalogRepository.saveAndFlush(testCatalogItem());
        EndpointCommand command = commandRepository.saveAndFlush(testInstallCommand(device, catalog));

        EndpointInstallAudit audit = new EndpointInstallAudit();
        audit.setTenantId(TENANT_A);
        audit.setDeviceId(device.getId());
        audit.setCommandId(command.getId());
        audit.setCatalogItemId(catalog.getId());
        audit.setCatalogPackageId(catalog.getPackageId());
        audit.setCatalogRowVersion(catalog.getVersion());
        audit.setPreflightDecision(InstallPreflightDecisionRecorded.PASS);
        audit.setPreflightDecisionAt(Instant.now());
        audit.setPreflightWarnCodes(List.of());
        audit.setActorSubject("alice@example.com");
        audit.setApprovalSubject(null);
        audit.setResultStatus(CommandResultStatus.SUCCEEDED);
        audit.setExitCode(0);
        audit.setReportedAt(Instant.now());
        audit.setStartedAt(Instant.now().minusSeconds(30));
        audit.setFinishedAt(Instant.now());
        audit.setPostVerification(InstallPostVerification.SATISFIED);
        audit.setDetectedPackageId("7zip.7zip");
        audit.setDetectedVersion("24.07");
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("status", "SATISFIED");
        evidence.put("ruleType", "WINGET_PACKAGE");
        audit.setPostVerificationEvidence(evidence);
        Map<String, Object> redacted = new HashMap<>();
        redacted.put("stage", "post_install");
        redacted.put("exitCode", 0);
        audit.setRedactedPayload(redacted);

        EndpointInstallAudit saved = installAuditRepository.saveAndFlush(audit);
        entityManager.clear();
        EndpointInstallAudit reloaded = installAuditRepository
                .findByTenantIdAndId(TENANT_A, saved.getId()).orElseThrow();

        assertThat(reloaded.getCatalogItemId()).isEqualTo(catalog.getId());
        assertThat(reloaded.getResultStatus()).isEqualTo(CommandResultStatus.SUCCEEDED);
        assertThat(reloaded.getPostVerification()).isEqualTo(InstallPostVerification.SATISFIED);
        assertThat(reloaded.getPostVerificationEvidence())
                .containsEntry("status", "SATISFIED");
        assertThat(reloaded.getRedactedPayload())
                .containsEntry("stage", "post_install");
        assertThat(reloaded.getRowVersion()).isNotNull();
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void installAuditRepositoryByCommandIdLookupReturnsSavedRow() {
        EndpointDevice device = deviceRepository.saveAndFlush(testDevice());
        EndpointSoftwareCatalogItem catalog = catalogRepository.saveAndFlush(testCatalogItem());
        EndpointCommand command = commandRepository.saveAndFlush(testInstallCommand(device, catalog));

        EndpointInstallAudit audit = new EndpointInstallAudit();
        audit.setTenantId(TENANT_A);
        audit.setDeviceId(device.getId());
        audit.setCommandId(command.getId());
        audit.setCatalogItemId(catalog.getId());
        audit.setCatalogPackageId(catalog.getPackageId());
        audit.setCatalogRowVersion(catalog.getVersion());
        audit.setPreflightDecision(InstallPreflightDecisionRecorded.PASS);
        audit.setPreflightDecisionAt(Instant.now());
        audit.setPreflightWarnCodes(List.of());
        audit.setActorSubject("alice@example.com");
        audit.setResultStatus(CommandResultStatus.FAILED);
        audit.setReportedAt(Instant.now());
        audit.setPostVerification(InstallPostVerification.UNSATISFIED);
        installAuditRepository.saveAndFlush(audit);

        EndpointInstallAudit byCommand = installAuditRepository
                .findByCommandId(command.getId()).orElseThrow();
        assertThat(byCommand.getResultStatus()).isEqualTo(CommandResultStatus.FAILED);
        assertThat(byCommand.getPostVerification()).isEqualTo(InstallPostVerification.UNSATISFIED);
    }

    private static EndpointDevice testDevice() {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(TENANT_A);
        device.setHostname("install-audit-test-pc");
        device.setOsType(OsType.WINDOWS);
        device.setStatus(com.example.endpointadmin.model.DeviceStatus.ONLINE);
        return device;
    }

    private static EndpointSoftwareCatalogItem testCatalogItem() {
        EndpointSoftwareCatalogItem item = new EndpointSoftwareCatalogItem();
        item.setTenantId(TENANT_A);
        item.setCatalogItemId("7zip-install-audit-test-" + UUID.randomUUID());
        item.setStatus(CatalogItemStatus.APPROVED);
        item.setProvider(CatalogProvider.WINGET);
        item.setSourceType(CatalogSourceType.WINGET);
        item.setSourceName("winget");
        item.setSourceTrust(CatalogSourceTrust.WINGET_COMMUNITY_REVIEWED);
        item.setPackageId("7zip.7zip");
        item.setDisplayName("7-Zip");
        item.setPublisher("Igor Pavlov");
        item.setVersionPolicyType(CatalogVersionPolicyType.MINIMUM);
        item.setVersionPolicyValue("24.0");
        item.setInstallerType(CatalogInstallerType.WINGET_SILENT);
        item.setRiskTier(CatalogRiskTier.LOW);
        item.setEnabled(true);
        item.setCreatedBySubject("alice@example.com");
        item.setLastUpdatedBySubject("alice@example.com");
        item.setApprovedBySubject("bob@example.com");
        item.setApprovedAt(Instant.now());
        HashMap<String, Object> detection = new HashMap<>();
        detection.put("type", "WINGET_PACKAGE");
        detection.put("wingetPackageId", "7zip.7zip");
        item.setDetectionRule(detection);
        return item;
    }

    private static EndpointCommand testInstallCommand(EndpointDevice device,
                                                       EndpointSoftwareCatalogItem catalog) {
        EndpointCommand command = new EndpointCommand();
        command.setTenantId(TENANT_A);
        command.setDevice(device);
        command.setCommandType(CommandType.INSTALL_SOFTWARE);
        command.setIdempotencyKey("install-audit-test-" + UUID.randomUUID());
        command.setStatus(CommandStatus.QUEUED);
        command.setApprovalStatus(com.example.endpointadmin.model.ApprovalStatus.NOT_REQUIRED);
        Map<String, Object> payload = new HashMap<>();
        payload.put("catalogItemId", catalog.getCatalogItemId());
        payload.put("catalogItemUuid", catalog.getId().toString());
        payload.put("catalogPackageId", catalog.getPackageId());
        payload.put("catalogRowVersion", catalog.getVersion());
        payload.put("preflightDecision", "PASS");
        payload.put("preflightDecisionAt", Instant.now().toString());
        command.setPayload(payload);
        command.setPriority(100);
        command.setAttemptCount(0);
        command.setMaxAttempts(3);
        command.setVisibleAfterAt(Instant.now());
        command.setIssuedBySubject("alice@example.com");
        command.setIssuedAt(Instant.now());
        return command;
    }
}
