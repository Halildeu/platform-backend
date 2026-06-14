package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.AdminManagedRootCreateRequest;
import com.example.endpointadmin.dto.v1.admin.AdminManagedRootResponse;
import com.example.endpointadmin.dto.v1.admin.AdminManagedRootSetEnabledRequest;
import com.example.endpointadmin.model.EndpointBackupDryrunManagedRoot;
import com.example.endpointadmin.repository.EndpointBackupDryrunManagedRootRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Faz 22.8A.3a (#648) — managed-root registry service tests. Pure H2 (no PG),
 * service constructed directly so feature-flag / per-tenant configs vary per
 * test.
 */
@IsolatedH2DataJpaTest
class EndpointBackupDryrunRegistryServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String ROOT_REF = "managed_root:33333333-3333-3333-3333-333333333333";

    @Autowired
    private EndpointBackupDryrunManagedRootRepository rootRepository;

    private EndpointBackupDryrunRegistryService enabledService() {
        // feature on + this tenant opted in
        return new EndpointBackupDryrunRegistryService(rootRepository, true, TENANT.toString());
    }

    private AdminTenantContext ctx() {
        return new AdminTenantContext(TENANT, "admin@example.com");
    }

    private AdminManagedRootCreateRequest validCreate() {
        return new AdminManagedRootCreateRequest(ROOT_REF, "managed/it-folder",
                "C:\\Users\\Acme\\OneDrive - Acme\\Shared", true);
    }

    // ---- happy path -------------------------------------------------------

    @Test
    void registerStoresRootAndReturnsPathFreeResponse() {
        AdminManagedRootResponse resp = enabledService().register(ctx(), validCreate());
        assertThat(resp.rootRef()).isEqualTo(ROOT_REF);
        assertThat(resp.pathClass()).isEqualTo("managed/it-folder");
        assertThat(resp.companyManaged()).isTrue();
        assertThat(resp.enabled()).isTrue();
        assertThat(resp.rootVersion()).isEqualTo(1);

        // The raw localPath is persisted INTERNALLY...
        EndpointBackupDryrunManagedRoot stored =
                rootRepository.findByTenantIdAndRootRef(TENANT, ROOT_REF).orElseThrow();
        assertThat(stored.getLocalPath()).isEqualTo("C:\\Users\\Acme\\OneDrive - Acme\\Shared");
        // path-free actor trail (Codex 019ec45e round-2 — replaced the fake reason).
        assertThat(stored.getCreatedBy()).isEqualTo("admin@example.com");
        assertThat(stored.getUpdatedBy()).isEqualTo("admin@example.com");
        // ...but the response DTO has NO localPath field at all (structural path-free).
        assertThat(AdminManagedRootResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("localPath");
    }

    @Test
    void listReturnsRegisteredRootsPathFree() {
        var svc = enabledService();
        svc.register(ctx(), validCreate());
        List<AdminManagedRootResponse> roots = svc.list(ctx(), 0, 50);
        assertThat(roots).hasSize(1);
        assertThat(roots.get(0).rootRef()).isEqualTo(ROOT_REF);
    }

    @Test
    void setEnabledTogglesRoot() {
        var svc = enabledService();
        AdminManagedRootResponse created = svc.register(ctx(), validCreate());
        AdminManagedRootResponse disabled = svc.setEnabled(ctx(), created.id(),
                new AdminManagedRootSetEnabledRequest(false));
        assertThat(disabled.enabled()).isFalse();
        assertThat(rootRepository.findByTenantIdAndEnabledTrueAndCompanyManagedTrueAndRootRefIn(
                TENANT, List.of(ROOT_REF))).isEmpty();
    }

    // ---- validation -------------------------------------------------------

    @Test
    void nonOpaqueRootRefRejected() {
        AdminManagedRootCreateRequest bad = new AdminManagedRootCreateRequest(
                "managed_root:C:\\Users\\Alice", "managed/it-folder", "C:\\data", true);
        assertThatThrownBy(() -> enabledService().register(ctx(), bad))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);
    }

    @Test
    void invalidPathClassRejected() {
        AdminManagedRootCreateRequest bad = new AdminManagedRootCreateRequest(
                ROOT_REF, "personal/desktop", "C:\\data", true);
        assertThatThrownBy(() -> enabledService().register(ctx(), bad))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void byodRootRejected() {
        // companyManaged=false → BYOD fail-closed in this slice.
        AdminManagedRootCreateRequest byod = new AdminManagedRootCreateRequest(
                ROOT_REF, "managed/onedrive-business", "C:\\Users\\alice\\personal", false);
        assertThatThrownBy(() -> enabledService().register(ctx(), byod))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void duplicateRootRefRejected() {
        var svc = enabledService();
        svc.register(ctx(), validCreate());
        assertThatThrownBy(() -> svc.register(ctx(), validCreate()))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);
    }

    // ---- fail-closed gates ------------------------------------------------

    @Test
    void featureDisabledReturns503() {
        var off = new EndpointBackupDryrunRegistryService(rootRepository, false, TENANT.toString());
        assertThatThrownBy(() -> off.register(ctx(), validCreate()))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void tenantNotOptedInReturns403() {
        // feature on, but only OTHER_TENANT is allowed → TENANT gets 403.
        var svc = new EndpointBackupDryrunRegistryService(rootRepository, true, OTHER_TENANT.toString());
        assertThatThrownBy(() -> svc.register(ctx(), validCreate()))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
    }

    @Test
    void emptyAllowListIsFailClosed() {
        // feature on, empty allow-list → NO tenant allowed (fail-closed).
        var svc = new EndpointBackupDryrunRegistryService(rootRepository, true, "");
        assertThatThrownBy(() -> svc.register(ctx(), validCreate()))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
    }
}
