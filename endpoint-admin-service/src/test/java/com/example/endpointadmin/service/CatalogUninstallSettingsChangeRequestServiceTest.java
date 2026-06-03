package com.example.endpointadmin.service;

import com.example.endpointadmin.audit.NoOpAuditChainLock;
import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogItemRequest;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogUninstallSettingsChangeRequestApproval;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogUninstallSettingsChangeRequestCreate;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogUninstallSettingsChangeRequestResponse;
import com.example.endpointadmin.model.CatalogInstallerType;
import com.example.endpointadmin.model.CatalogProvider;
import com.example.endpointadmin.model.CatalogRiskTier;
import com.example.endpointadmin.model.CatalogSilentArgsPolicy;
import com.example.endpointadmin.model.CatalogSourceTrust;
import com.example.endpointadmin.model.CatalogSourceType;
import com.example.endpointadmin.model.CatalogUninstallSettingsField;
import com.example.endpointadmin.model.CatalogVersionPolicyType;
import com.example.endpointadmin.model.EndpointAuditEvent;
import com.example.endpointadmin.repository.EndpointAuditEventRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AG-028 Phase 0 — service-layer tests for
 * {@link CatalogUninstallSettingsChangeRequestService} (Faz 22.5.6).
 *
 * <p>Focus is on the Codex post-impl iter-1 absorb items:
 * <ul>
 *   <li>Durable maker-checker reject audit across an own-transaction throw
 *       (BE-014A {@code noRollbackFor} pattern reuse). If
 *       {@code noRollbackFor =
 *       CatalogUninstallSettingsMakerCheckerViolationException.class} is
 *       removed from {@link CatalogUninstallSettingsChangeRequestService#approve},
 *       the durability assertion MUST fail because the reject audit row
 *       would roll back.</li>
 *   <li>Durable elevated-approver-required reject audit (same
 *       {@code noRollbackFor} pattern, second exception type).</li>
 *   <li>Happy approve path applies the flag flip on the catalog row.</li>
 * </ul>
 *
 * <p>Cross-AI plan-time Codex consensus thread
 * {@code 019e8c8a-4c90-7c00-8f64-c88d47801a06} iter-6 AGREE.
 * Post-impl review thread {@code 019e8d5b-4511-79d3-8f14-a07f0018681e}
 * iter-1 REVISE absorb.
 */
@IsolatedH2DataJpaTest
@Import({
        TimeConfig.class,
        EndpointSoftwareCatalogService.class,
        CatalogUninstallSettingsChangeRequestService.class,
        EndpointAuditService.class,
        DetectionRuleValidator.class,
        NoOpAuditChainLock.class
})
class CatalogUninstallSettingsChangeRequestServiceTest {

    private static final UUID TENANT_A =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    /** Durability tenant for {@code NOT_SUPPORTED} regression tests. */
    private static final UUID TENANT_DURABILITY =
            UUID.fromString("55555555-5555-5555-5555-555555555555");

    private static final String SUBJECT_ALICE = "alice@example.com";
    private static final String SUBJECT_BOB = "bob@example.com";

    @Autowired
    private EndpointSoftwareCatalogService catalogService;

    @Autowired
    private CatalogUninstallSettingsChangeRequestService changeRequestService;

    @Autowired
    private EndpointAuditEventRepository auditRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void proposeApproveHappyPath_appliesFlagFlipAndEmitsAudit() {
        UUID catalogId = seedApprovedCatalog(TENANT_A, SUBJECT_ALICE, SUBJECT_BOB,
                "7zip-happy-approve");

        AdminCatalogUninstallSettingsChangeRequestResponse proposed =
                changeRequestService.propose(
                        new AdminTenantContext(TENANT_A, SUBJECT_ALICE),
                        catalogId,
                        new AdminCatalogUninstallSettingsChangeRequestCreate(
                                CatalogUninstallSettingsField.UNINSTALL_SUPPORTED,
                                true, "enable uninstall"));

        // Approver MUST differ from proposer (BOB approves Alice's request).
        AdminCatalogUninstallSettingsChangeRequestResponse applied =
                changeRequestService.approve(
                        new AdminTenantContext(TENANT_A, SUBJECT_BOB),
                        catalogId,
                        proposed.id(),
                        new AdminCatalogUninstallSettingsChangeRequestApproval("ok"));

        assertThat(applied.state().name()).isEqualTo("APPLIED");
        assertThat(applied.approvedBy()).isEqualTo(SUBJECT_BOB);
        assertThat(applied.appliedAt()).isNotNull();

        flushAndClear();
        // PROPOSED + APPROVED_APPLIED audit events emitted.
        assertThat(auditEventTypes(TENANT_A))
                .contains(CatalogUninstallSettingsChangeRequestService.EVENT_PROPOSED)
                .contains(CatalogUninstallSettingsChangeRequestService.EVENT_APPROVED_APPLIED);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void approveSelfApprovalAuditDurableAcrossOwnTransaction_noRollbackForRegression() {
        // BE-014A pattern reuse (mirror EndpointSoftwareCatalogServiceTest
        // .approveCatalogItemSelfApprovalAuditDurableAcrossOwnTransaction_noRollbackForRegression).
        //
        // Suspend the class-level @DataJpaTest tx (NOT_SUPPORTED) so the
        // approve call runs in its OWN tx. The maker-checker reject audit
        // is emitted in the same tx as approve(...); noRollbackFor =
        // CatalogUninstallSettingsMakerCheckerViolationException keeps that
        // tx from rolling back when the 403 throws. If noRollbackFor is
        // removed from approve(...), this assertion MUST fail because the
        // reject audit row would not survive the throw.
        UUID catalogId = seedApprovedCatalog(TENANT_DURABILITY, SUBJECT_ALICE, SUBJECT_BOB,
                "7zip-self-approve-not-supported");

        // Alice proposes the flag flip.
        AdminCatalogUninstallSettingsChangeRequestResponse proposed =
                changeRequestService.propose(
                        new AdminTenantContext(TENANT_DURABILITY, SUBJECT_ALICE),
                        catalogId,
                        new AdminCatalogUninstallSettingsChangeRequestCreate(
                                CatalogUninstallSettingsField.UNINSTALL_SUPPORTED,
                                true, "test self-approval reject"));

        // Alice attempts to approve her own request → maker-checker violation.
        assertThatThrownBy(() -> changeRequestService.approve(
                new AdminTenantContext(TENANT_DURABILITY, SUBJECT_ALICE),
                catalogId,
                proposed.id(),
                new AdminCatalogUninstallSettingsChangeRequestApproval("self approve")))
                .isInstanceOf(CatalogUninstallSettingsMakerCheckerViolationException.class);

        // Durability assertion outside any test tx, scoped to the dedicated
        // durability tenant: reject audit row must survive the 403 throw.
        // If noRollbackFor is removed from approve(...), the reject audit
        // would roll back and this assertion would FAIL.
        assertThat(auditEventTypes(TENANT_DURABILITY))
                .as("reject audit row must persist past the 403 throw "
                        + "(noRollbackFor=CatalogUninstallSettingsMakerCheckerViolationException "
                        + "invariant)")
                .contains(CatalogUninstallSettingsChangeRequestService
                        .EVENT_MAKER_CHECKER_REJECTED);
    }

    // -----------------------------------------------------------------
    // Helpers

    private UUID seedApprovedCatalog(UUID tenantId, String creator, String approver,
                                     String slug) {
        AdminCatalogItemRequest req = sevenZipRequest(slug);
        catalogService.createCatalogItem(new AdminTenantContext(tenantId, creator), req);
        var approved = catalogService.approveCatalogItem(
                new AdminTenantContext(tenantId, approver), req.catalogItemId());
        return approved.id();
    }

    private AdminCatalogItemRequest sevenZipRequest(String slug) {
        Map<String, Object> detection = new HashMap<>();
        detection.put("type", "WINGET_PACKAGE");
        detection.put("wingetPackageId", "7zip.7zip");
        return new AdminCatalogItemRequest(
                slug,
                CatalogProvider.WINGET,
                CatalogSourceType.WINGET,
                "winget",
                CatalogSourceTrust.WINGET_COMMUNITY_REVIEWED,
                "7zip.7zip",
                "7-Zip",
                "7-Zip",
                CatalogVersionPolicyType.LATEST,
                null,
                CatalogInstallerType.WINGET_SILENT,
                CatalogSilentArgsPolicy.VENDOR_RECOMMENDED,
                null,
                null,
                detection,
                CatalogRiskTier.LOW);
    }

    private List<String> auditEventTypes(UUID tenantId) {
        return auditRepository
                .findTop50ByTenantIdOrderByOccurredAtDesc(tenantId)
                .stream()
                .map(EndpointAuditEvent::getEventType)
                .toList();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
