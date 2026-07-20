package com.example.ethics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaProperties;
import com.example.ethics.security.EthicsAuthorization;
import com.example.ethics.security.StaffContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EthicsAuthorizationTest {
    private final OpenFgaAuthzService openFga = mock(OpenFgaAuthzService.class);
    private final OpenFgaProperties properties = enabledProperties();
    private final EthicsAuthorization authorization = new EthicsAuthorization(openFga, properties);
    private final StaffContext staff = new StaffContext(UUID.randomUUID(), "staff-test");

    @Test
    void denyAndUnavailableBothFailClosed() {
        UUID caseId = UUID.randomUUID();
        when(openFga.checkNoCacheResult(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(result(false, "no_relation"));
        assertThat(authorization.can(staff, "case_viewer", caseId)).isFalse();
        verify(openFga).checkNoCacheResult(
                staff.subject(),
                "case_viewer",
                EthicsAuthorization.PRODUCT_OBJECT,
                staff.orgId().toString());

        reset(openFga);
        when(openFga.checkNoCacheResult(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("synthetic outage"));
        assertThat(authorization.can(staff, "case_viewer", caseId)).isFalse();
    }

    @Test
    void conflictAndRecusalOverrideProductAccessWithoutAnExistenceSignal() {
        UUID caseId = UUID.randomUUID();
        grantProduct("case_viewer");
        when(openFga.checkNoCacheResult(
                staff.subject(), "conflicted", EthicsAuthorization.CASE_OBJECT, caseId.toString()))
                .thenReturn(result(true, "granted"));
        assertThat(authorization.can(staff, "case_viewer", caseId)).isFalse();

        reset(openFga);
        grantProduct("case_viewer");
        when(openFga.checkNoCacheResult(
                staff.subject(), "conflicted", EthicsAuthorization.CASE_OBJECT, caseId.toString()))
                .thenReturn(result(false, "no_relation"));
        when(openFga.checkNoCacheResult(
                staff.subject(), "recused", EthicsAuthorization.CASE_OBJECT, caseId.toString()))
                .thenReturn(result(true, "granted"));
        assertThat(authorization.can(staff, "case_viewer", caseId)).isFalse();
    }

    @Test
    void partialOutageAfterProductAllowFailsClosed() {
        UUID caseId = UUID.randomUUID();
        grantProduct("case_viewer");
        when(openFga.checkNoCacheResult(
                staff.subject(), "conflicted", EthicsAuthorization.CASE_OBJECT, caseId.toString()))
                .thenReturn(result(false, "unavailable"));
        assertThat(authorization.can(staff, "case_viewer", caseId)).isFalse();

        reset(openFga);
        grantProduct("case_viewer");
        when(openFga.checkNoCacheResult(
                staff.subject(), "conflicted", EthicsAuthorization.CASE_OBJECT, caseId.toString()))
                .thenReturn(result(false, "no_relation"));
        when(openFga.checkNoCacheResult(
                staff.subject(), "recused", EthicsAuthorization.CASE_OBJECT, caseId.toString()))
                .thenReturn(result(false, "unavailable"));
        assertThat(authorization.can(staff, "case_viewer", caseId)).isFalse();
    }

    @Test
    void productAccessWithoutConflictOrRecusalAllowsTheCase() {
        UUID caseId = UUID.randomUUID();
        grantProduct("case_viewer");
        when(openFga.checkNoCacheResult(
                staff.subject(), "conflicted", EthicsAuthorization.CASE_OBJECT, caseId.toString()))
                .thenReturn(result(false, "no_relation"));
        when(openFga.checkNoCacheResult(
                staff.subject(), "recused", EthicsAuthorization.CASE_OBJECT, caseId.toString()))
                .thenReturn(result(false, "no_relation"));
        assertThat(authorization.can(staff, "case_viewer", caseId)).isTrue();
    }

    private void grantProduct(String relation) {
        when(openFga.checkNoCacheResult(
                staff.subject(), relation, EthicsAuthorization.PRODUCT_OBJECT, staff.orgId().toString()))
                .thenReturn(result(true, "granted"));
    }

    private static OpenFgaAuthzService.CheckResult result(boolean allowed, String reason) {
        return new OpenFgaAuthzService.CheckResult(allowed, reason);
    }

    private static OpenFgaProperties enabledProperties() {
        var value = new OpenFgaProperties();
        value.setEnabled(true);
        value.setStoreId("test-store");
        value.setModelId("test-model");
        return value;
    }
}
