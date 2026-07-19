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
        when(openFga.checkNoCache(anyString(), anyString(), anyString(), anyString())).thenReturn(false);
        assertThat(authorization.can(staff, "case_viewer", UUID.randomUUID())).isFalse();
        verify(openFga).checkNoCache(
                staff.subject(),
                "case_viewer",
                EthicsAuthorization.PRODUCT_OBJECT,
                staff.orgId().toString());
        when(openFga.checkNoCache(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("synthetic outage"));
        assertThat(authorization.can(staff, "case_viewer", UUID.randomUUID())).isFalse();
    }

    @Test
    void conflictAndRecusalOverrideProductAccessWithoutAnExistenceSignal() {
        UUID caseId = UUID.randomUUID();
        when(openFga.checkNoCache(staff.subject(), "case_viewer", EthicsAuthorization.PRODUCT_OBJECT, staff.orgId().toString()))
                .thenReturn(true);
        when(openFga.checkNoCache(staff.subject(), "conflicted", "ethics_case", caseId.toString()))
                .thenReturn(true);
        assertThat(authorization.can(staff, "case_viewer", caseId)).isFalse();

        reset(openFga);
        when(openFga.checkNoCache(staff.subject(), "case_viewer", EthicsAuthorization.PRODUCT_OBJECT, staff.orgId().toString()))
                .thenReturn(true);
        when(openFga.checkNoCache(staff.subject(), "conflicted", "ethics_case", caseId.toString()))
                .thenReturn(false);
        when(openFga.checkNoCache(staff.subject(), "recused", "ethics_case", caseId.toString()))
                .thenReturn(true);
        assertThat(authorization.can(staff, "case_viewer", caseId)).isFalse();
    }

    @Test
    void productAccessWithoutConflictOrRecusalAllowsTheCase() {
        UUID caseId = UUID.randomUUID();
        when(openFga.checkNoCache(staff.subject(), "case_viewer", EthicsAuthorization.PRODUCT_OBJECT, staff.orgId().toString()))
                .thenReturn(true);
        when(openFga.checkNoCache(staff.subject(), "conflicted", "ethics_case", caseId.toString()))
                .thenReturn(false);
        when(openFga.checkNoCache(staff.subject(), "recused", "ethics_case", caseId.toString()))
                .thenReturn(false);
        assertThat(authorization.can(staff, "case_viewer", caseId)).isTrue();
    }

    private static OpenFgaProperties enabledProperties() {
        var value = new OpenFgaProperties();
        value.setEnabled(true);
        value.setStoreId("test-store");
        value.setModelId("test-model");
        return value;
    }
}
